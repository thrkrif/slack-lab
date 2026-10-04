package com.slack.lab.adapter.docs;

import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.PortResult;
import com.slack.lab.core.model.SourceDocument;
import com.slack.lab.core.port.DocumentSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 로컬 디렉터리의 마크다운 문서(첫 {@link DocumentSource} 구현). 문서 ID는 front matter의 {@code id} 또는 파일 이름
 * (디렉터리·확장자 제외)이고, 둘 다 <b>공개해도 되는 불투명 식별자</b>로 정리한다 — 경로는 어댑터 안에서만 쥔다. 심볼릭
 * 링크는 따라가지 않는다(디렉터리 밖 파일을 색인하는 우회 방지). 출처가 읽히지 않으면 빈 목록이 아니라 실패다: 경로 설정 실수가
 * "문서가 모두 사라졌다"로 읽혀 색인이 지워지면 안 된다.
 */
public class LocalMarkdownDocumentSource implements DocumentSource {

    private static final long MAX_BYTES = 1_000_000;
    private static final int MAX_TITLE = 200;

    private final Path root;
    private final boolean configured;

    public LocalMarkdownDocumentSource(String docsDir) {
        this.configured = docsDir != null && !docsDir.isBlank();
        this.root = Path.of(configured ? docsDir : ".");
    }

    @Override
    public PortResult<List<SourceDocument>> list() {
        if (!configured) {
            return fail("docs_dir_not_configured"); // rag.index.docs-dir(RAG_DOCS_DIR): 저장소 밖 문서 디렉터리
        }
        if (!Files.isDirectory(root)) {
            return fail("docs_dir_not_found");
        }
        Path realRoot;
        List<Path> files;
        try {
            realRoot = root.toRealPath();
            try (Stream<Path> walk = Files.walk(realRoot)) {
                files = walk.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                        .filter(Files::isRegularFile).filter(p -> !Files.isSymbolicLink(p)).sorted().toList();
            }
        } catch (IOException e) {
            return fail("walk:" + e.getClass().getSimpleName());
        }
        List<SourceDocument> docs = new ArrayList<>();
        Map<String, Path> seen = new HashMap<>();
        for (Path file : files) {
            try {
                if (Files.size(file) > MAX_BYTES) {
                    return fail("too_large:" + publicName(file));
                }
                String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                PortResult<SourceDocument> parsed = parse(file, raw);
                if (parsed instanceof PortResult.Failed<SourceDocument> bad) {
                    return PortResult.failed(bad.error());
                }
                SourceDocument doc = ((PortResult.Success<SourceDocument>) parsed).value();
                if (seen.put(doc.id(), file) != null) {
                    return fail("duplicate_id:" + doc.id());
                }
                docs.add(doc);
            } catch (IOException e) {
                return fail("read:" + publicName(file) + ":" + e.getClass().getSimpleName());
            }
        }
        return PortResult.ok(docs);
    }

    static PortResult<SourceDocument> parse(Path file, String raw) {
        List<String> lines = List.of(raw.replace("\r\n", "\n").split("\n", -1));
        Map<String, String> meta = new HashMap<>();
        int bodyStart = 0;
        if (!lines.isEmpty() && "---".equals(lines.get(0).stripTrailing())) {
            int end = -1;
            for (int i = 1; i < lines.size(); i++) {
                if ("---".equals(lines.get(i).stripTrailing())) { // 독립된 구분자 행만 종료선으로 본다("---not-end"는 본문)
                    end = i;
                    break;
                }
            }
            if (end > 0) {
                for (String line : lines.subList(1, end)) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        meta.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), unquote(line.substring(colon + 1).strip()));
                    }
                }
                bodyStart = end + 1;
            }
        }
        String text = String.join("\n", lines.subList(bodyStart, lines.size()));
        String stem = stem(file);
        String rawId = meta.getOrDefault("id", "").strip();
        // 경로 모양의 ID는 정리해서 쓰지 않고 거부한다 — 정리하면 "etc-passwd"처럼 경로 조각이 공개 ID로 남는다.
        if (rawId.contains("/") || rawId.contains("\\") || rawId.contains("..")) {
            return PortResult.failed(ErrorInfo.of(ErrorCode.DOCUMENT_SOURCE_FAILED, "invalid_id:" + sanitizeId(stem)));
        }
        String id = sanitizeId(rawId);
        if (id.isEmpty()) {
            id = sanitizeId(stem);
        }
        if (id.isEmpty()) {
            id = "doc";
        }
        String title = meta.getOrDefault("title", "").strip();
        if (title.isEmpty()) {
            title = firstHeading(text);
        }
        if (title.isEmpty()) {
            title = id;
        }
        if (title.length() > MAX_TITLE) {
            title = title.substring(0, MAX_TITLE);
        }
        // 제목은 답변의 "참고 문서"에 표시되므로 제목만 바뀌어도 다시 색인하도록 해시에 넣는다.
        return PortResult.ok(new SourceDocument(id, title, text, sha256(title + "\u0000" + text)));
    }

    private static String firstHeading(String text) {
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (t.startsWith("#")) {
                return t.replaceFirst("^#+\\s*", "").strip();
            }
            if (!t.isEmpty()) {
                return "";
            }
        }
        return "";
    }

    // 영숫자·한글·점·밑줄·하이픈만 남기고 나머지(경로 구분자 포함)는 '-'로 바꾼다. 앞뒤 구분 문자는 뗀다.
    static String sanitizeId(String s) {
        String cleaned = s.strip().replaceAll("[^A-Za-z0-9._\\-가-힣]", "-").replaceAll("-{2,}", "-");
        cleaned = cleaned.replaceAll("^[.\\-]+|[.\\-]+$", "");
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String stem(Path file) {
        String n = file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String publicName(Path file) {
        return sanitizeId(stem(file));
    }

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PortResult<List<SourceDocument>> fail(String detail) {
        return PortResult.failed(ErrorInfo.of(ErrorCode.DOCUMENT_SOURCE_FAILED, detail));
    }
}
