package com.slack.lab.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * RAG를 켠 채 위험한 조합이면 기동을 거부한다(규칙 4 "조용한 실패 금지"). 경고 로그만으로는 운영자가 로그를 못 보면 그대로
 * 유출되므로 {@code POSTGRES_PASSWORD}를 기본값 없이 요구한 것과 같은 철학으로 시작 단계에서 막는다.
 *
 * <p>호스트는 URL 문자열 비교가 아니라 파싱해서 정확히 비교한다 — {@code localhost.evil.com}이나
 * {@code http://localhost@evil.com}이 통과하면 안 된다. 리다이렉트는 두 클라이언트 모두 따라가지 않고, DNS 재바인딩은
 * 이 검사의 범위 밖이다(호스트 이름만 본다).
 */
@Component
@ConditionalOnRole({AppRole.WORKER, AppRole.INDEXER, AppRole.ALL})
public class RagGuard {

    private static final Logger log = LoggerFactory.getLogger(RagGuard.class);
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    public RagGuard(RagProperties rag, LlmProperties llm, Environment env) {
        // 색인 CLI는 LLM을 쓰지 않으므로 LLM 호스트·예산 검사를 적용하지 않는다(무관한 허용 플래그를 요구하면 안 된다).
        boolean llmUsed = OnRoleCondition.currentRole(env) != AppRole.INDEXER;
        List<String> violations = check(rag, llm, llmUsed);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("RAG 설정 위반: " + String.join("; ", violations));
        }
        if (rag.enabled()) {
            if (rag.allowExternalEmbedding() && !isAllowedHost(rag.embeddingBaseUrl(), rag.allowedHosts())) {
                log.warn("RAG 외부 임베딩 엔드포인트 허용됨: 문서 원문이 호스트 {} 로 전송된다(rag.allow-external-embedding=true)",
                        hostOf(rag.embeddingBaseUrl()));
            }
            if (llmUsed && usesExternalLlm(llm) && rag.allowExternalLlm() && !isAllowedHost(llm.baseUrl(), rag.allowedHosts())) {
                log.warn("RAG 외부 LLM 엔드포인트 허용됨: 검색된 문서 조각과 질문이 호스트 {} 로 전송된다(rag.allow-external-llm=true)",
                        hostOf(llm.baseUrl()));
            }
        }
    }

    static List<String> check(RagProperties rag, LlmProperties llm) {
        return check(rag, llm, true);
    }

    static List<String> check(RagProperties rag, LlmProperties llm, boolean llmUsed) {
        List<String> v = new ArrayList<>();
        if (!rag.enabled()) {
            return v;
        }
        if (rag.embeddingModel().isBlank()) {
            v.add("rag.embedding-model(RAG_EMBEDDING_MODEL)이 필요하다. `ollama list`로 확인한 ID를 쓴다");
        }
        if (rag.searchDeadlineMs() <= 0 || rag.connectTimeoutMs() <= 0) {
            v.add("rag.search-deadline-ms와 rag.connect-timeout-ms는 양수여야 한다");
        }
        if (rag.embeddingDimension() <= 0) {
            v.add("rag.embedding-dimension(RAG_EMBEDDING_DIMENSION)이 필요하다(예: bge-m3는 1024)");
        }
        if (!isAllowedHost(rag.embeddingBaseUrl(), rag.allowedHosts()) && !rag.allowExternalEmbedding()) {
            v.add("rag.embedding-base-url 호스트 '" + hostOf(rag.embeddingBaseUrl())
                    + "'가 rag.allowed-hosts에 없다 — 문서 원문이 밖으로 나간다. 의도했다면 rag.allow-external-embedding=true,"
                    + " 사내 호스트라면 rag.allowed-hosts에 추가한다");
        }
        if (llmUsed && usesExternalLlm(llm) && !isAllowedHost(llm.baseUrl(), rag.allowedHosts()) && !rag.allowExternalLlm()) {
            v.add("llm.base-url 호스트 '" + hostOf(llm.baseUrl())
                    + "'가 rag.allowed-hosts에 없다 — 검색된 문서 조각과 질문이 밖으로 나간다. 의도했다면"
                    + " rag.allow-external-llm=true, 사내 호스트라면 rag.allowed-hosts에 추가한다");
        }
        if (llmUsed && rag.searchDeadlineMs() >= llm.deadlineMs()) {
            v.add("rag.search-deadline-ms < llm.deadline-ms 이어야 한다(검색은 LLM 예산 안에서 쓴다)");
        }
        return v;
    }

    // Echo 클라이언트는 외부 호출이 없다.
    private static boolean usesExternalLlm(LlmProperties llm) {
        return llm.client() != LlmProperties.Client.ECHO;
    }

    /** URL의 호스트가 허용 목록에 있는가. 파싱할 수 없거나 http(s)가 아니면 허용하지 않는다. */
    static boolean isAllowedHost(String url, List<String> allowed) {
        String host = hostOf(url);
        if (host == null) {
            return false;
        }
        for (String raw : allowed) {
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.equals(host)) {
                return true;
            }
            if (entry.startsWith("*.") && host.endsWith(entry.substring(1)) && host.length() > entry.length() - 1) {
                return true;
            }
            if (entry.contains("/") && inCidr(host, entry)) {
                return true;
            }
        }
        return false;
    }

    /** 소문자, IPv6 대괄호·끝의 점을 뗀 호스트. 못 구하면 null. */
    static String hostOf(String url) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            while (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            return host.isEmpty() ? null : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // IPv4 리터럴만 비교한다. 호스트 이름을 DNS로 풀지 않는다(검사 시점과 호출 시점의 결과가 달라질 수 있다).
    private static boolean inCidr(String host, String cidr) {
        var h = IPV4.matcher(host);
        String[] parts = cidr.split("/", 2);
        var n = IPV4.matcher(parts[0]);
        if (!h.matches() || !n.matches()) {
            return false;
        }
        int bits;
        try {
            bits = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (bits < 0 || bits > 32) {
            return false;
        }
        long a = toLong(h);
        long b = toLong(n);
        if (a < 0 || b < 0) {
            return false;
        }
        long mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
        return (a & mask) == (b & mask);
    }

    private static long toLong(java.util.regex.Matcher m) {
        long out = 0;
        for (int i = 1; i <= 4; i++) {
            int octet = Integer.parseInt(m.group(i));
            if (octet > 255) {
                return -1;
            }
            out = (out << 8) | octet;
        }
        return out;
    }
}
