package com.slack.lab.adapter.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.PortResult;
import com.slack.lab.core.model.SourceDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalMarkdownDocumentSourceTest {

    @TempDir
    Path dir;

    List<SourceDocument> docs() {
        var r = new LocalMarkdownDocumentSource(dir.toString()).list();
        assertThat(r).isInstanceOf(PortResult.Success.class);
        return ((PortResult.Success<List<SourceDocument>>) r).value();
    }

    String failure() {
        var r = new LocalMarkdownDocumentSource(dir.toString()).list();
        assertThat(r).isInstanceOf(PortResult.Failed.class);
        var e = ((PortResult.Failed<List<SourceDocument>>) r).error();
        assertThat(e.code()).isEqualTo(ErrorCode.DOCUMENT_SOURCE_FAILED);
        return e.detail();
    }

    void write(String rel, String content) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void ID는_front_matter_또는_파일_이름이고_제목은_front_matter나_첫_제목이다() throws Exception {
        write("a.md", "---\nid: DB-003\ntitle: \"커넥션 풀 고갈\"\n---\n본문 A");
        write("sub/dir/ops-012.md", "# 점검 절차\n\n본문 B");
        write("c.md", "제목 없는 본문");

        var byId = docs().stream().collect(java.util.stream.Collectors.toMap(SourceDocument::id, d -> d));

        assertThat(byId).containsOnlyKeys("DB-003", "ops-012", "c");
        assertThat(byId.get("DB-003").title()).isEqualTo("커넥션 풀 고갈");
        assertThat(byId.get("DB-003").content()).isEqualTo("본문 A");
        assertThat(byId.get("ops-012").title()).isEqualTo("점검 절차");
        assertThat(byId.get("c").title()).isEqualTo("c");
    }

    @Test
    void ID에는_경로가_담기지_않는다() throws Exception {
        write("team/db/secret-path.md", "내용");

        assertThat(docs()).extracting(SourceDocument::id).containsExactly("secret-path");
    }

    @Test
    void 경로_모양의_front_matter_ID는_정리하지_않고_거부한다() throws Exception {
        write("b.md", "---\nid: ../../etc/passwd\n---\n내용");

        assertThat(failure()).isEqualTo("invalid_id:b");
        write("b.md", "---\nid: team/db/x\n---\n내용");
        assertThat(failure()).isEqualTo("invalid_id:b");
    }

    @Test
    void 제목만_바꿔도_해시가_달라져_다시_색인된다() throws Exception {
        write("a.md", "---\ntitle: 옛 제목\n---\n본문");
        String h1 = docs().get(0).contentHash();

        write("a.md", "---\ntitle: 새 제목\n---\n본문");

        assertThat(docs().get(0).contentHash()).isNotEqualTo(h1);
        assertThat(docs().get(0).title()).isEqualTo("새 제목");
    }

    @Test
    void front_matter_구분선은_독립된_행만_인정한다() throws Exception {
        // "---not-end"는 종료선이 아니다 → 진짜 종료선까지가 front matter
        write("a.md", "---\ntitle: 제목\n---not-end\nid: X\n---\n본문");
        assertThat(docs().get(0).title()).isEqualTo("제목");
        assertThat(docs().get(0).id()).isEqualTo("X");
        assertThat(docs().get(0).content()).isEqualTo("본문");

        // 빈 front matter도 본문과 구분된다
        write("a.md", "---\n---\n# 본문 제목\n내용");
        assertThat(docs().get(0).content()).startsWith("# 본문 제목");

        // 종료선이 없으면 front matter가 아니라 본문이다
        write("a.md", "---\ntitle: 닫히지 않음\n본문");
        assertThat(docs().get(0).content()).contains("title: 닫히지 않음");
    }

    @Test
    void 내용_해시는_내용이_같으면_같고_바뀌면_달라진다() throws Exception {
        write("a.md", "하나");
        String h1 = docs().get(0).contentHash();
        assertThat(docs().get(0).contentHash()).isEqualTo(h1);

        write("a.md", "둘");
        assertThat(docs().get(0).contentHash()).isNotEqualTo(h1);
    }

    @Test
    void 마크다운이_아닌_파일은_무시하고_빈_디렉터리는_빈_목록_성공이다() throws Exception {
        assertThat(docs()).isEmpty();
        write("notes.txt", "x");
        write("img.png", "x");
        assertThat(docs()).isEmpty();
    }

    @Test
    void 디렉터리가_없으면_빈_목록이_아니라_실패다() {
        var r = new LocalMarkdownDocumentSource(dir.resolve("없는경로").toString()).list();

        assertThat(r).isInstanceOf(PortResult.Failed.class);
        assertThat(((PortResult.Failed<List<SourceDocument>>) r).error().detail()).isEqualTo("docs_dir_not_found");
    }

    @Test
    void 같은_ID가_두_파일에_있으면_모호하므로_실패한다() throws Exception {
        write("x/same.md", "하나");
        write("y/same.md", "둘");

        assertThat(failure()).isEqualTo("duplicate_id:same");
    }

    @Test
    void 심볼릭_링크는_따라가지_않는다() throws Exception {
        Path outside = Files.createTempDirectory("rag-outside");
        try {
            Files.writeString(outside.resolve("secret.md"), "디렉터리 밖 문서");
            Files.createSymbolicLink(dir.resolve("link.md"), outside.resolve("secret.md"));
            Files.createSymbolicLink(dir.resolve("linkdir"), outside);
            write("real.md", "정상");

            assertThat(docs()).extracting(SourceDocument::id).containsExactly("real");
        } finally {
            Files.deleteIfExists(outside.resolve("secret.md"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void 너무_큰_파일은_실패하고_공개_이름만_알린다() throws Exception {
        write("dir1/big.md", "x".repeat(1_000_001));

        assertThat(failure()).isEqualTo("too_large:big");
    }
}
