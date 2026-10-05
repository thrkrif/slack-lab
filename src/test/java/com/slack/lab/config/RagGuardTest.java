package com.slack.lab.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 외부 유출 정책(ADR-9 스위치): 위험한 조합은 경고가 아니라 기동 거부다. */
class RagGuardTest {

    static final List<String> LOCAL = List.of("localhost", "127.0.0.1", "::1", "host.docker.internal");

    static RagProperties rag(boolean enabled, String embUrl, boolean allowEmb, boolean allowLlm, List<String> allowed) {
        return new RagProperties(enabled, embUrl, "bge-m3", 1024, 5_000, 3_000, true, allowLlm, allowEmb, allowed);
    }

    static LlmProperties llm(LlmProperties.Client client, String baseUrl) {
        return new LlmProperties(client, baseUrl, "m", 512, "30m", 50_000, 3_000, true);
    }

    @Test
    void 호스트는_URL_문자열이_아니라_파싱해서_정확히_비교한다() {
        assertThat(RagGuard.isAllowedHost("http://localhost:11434/v1", LOCAL)).isTrue();
        assertThat(RagGuard.isAllowedHost("HTTP://LOCALHOST.:11434/v1", LOCAL)).as("대소문자·끝의 점").isTrue();
        assertThat(RagGuard.isAllowedHost("http://[::1]:11434/v1", LOCAL)).isTrue();
        assertThat(RagGuard.isAllowedHost("http://host.docker.internal:11434/v1", LOCAL)).isTrue();

        assertThat(RagGuard.isAllowedHost("http://localhost.evil.com/v1", LOCAL)).as("접두 우회").isFalse();
        assertThat(RagGuard.isAllowedHost("http://evil.com/localhost", LOCAL)).as("경로에 이름").isFalse();
        assertThat(RagGuard.isAllowedHost("http://localhost@evil.com/v1", LOCAL)).as("userinfo 우회").isFalse();
        assertThat(RagGuard.isAllowedHost("http://127.0.0.1.evil.com/v1", LOCAL)).isFalse();
        assertThat(RagGuard.isAllowedHost("http://2130706433/v1", LOCAL)).as("정수 IP는 거부 쪽으로").isFalse();
        assertThat(RagGuard.isAllowedHost("ftp://localhost/v1", LOCAL)).as("http(s)만").isFalse();
        assertThat(RagGuard.isAllowedHost("not a url", LOCAL)).isFalse();
        assertThat(RagGuard.isAllowedHost("", LOCAL)).isFalse();
    }

    @Test
    void 접미사와_CIDR로_사내_호스트를_허용할_수_있다() {
        List<String> allowed = List.of("*.corp.internal", "10.0.0.0/8", "192.168.1.5");
        assertThat(RagGuard.isAllowedHost("https://llm.corp.internal/v1", allowed)).isTrue();
        assertThat(RagGuard.isAllowedHost("https://corp.internal/v1", allowed)).as("접미사 자체는 아님").isFalse();
        assertThat(RagGuard.isAllowedHost("https://llm.corp.internal.evil.com/v1", allowed)).isFalse();
        assertThat(RagGuard.isAllowedHost("http://10.20.30.40:8080/v1", allowed)).isTrue();
        assertThat(RagGuard.isAllowedHost("http://11.0.0.1/v1", allowed)).isFalse();
        assertThat(RagGuard.isAllowedHost("http://192.168.1.5/v1", allowed)).isTrue();
        assertThat(RagGuard.isAllowedHost("http://10.0.0.999/v1", allowed)).as("잘못된 옥텟").isFalse();
    }

    @Test
    void RAG가_꺼져_있으면_외부_URL이어도_검사하지_않는다() {
        var r = rag(false, "https://api.openai.example/v1", false, false, LOCAL);
        assertThat(RagGuard.check(r, llm(LlmProperties.Client.OLLAMA, "https://api.example.com/v1"))).isEmpty();
    }

    @Test
    void 외부_임베딩과_외부_LLM은_각각_따로_허용해야_한다() {
        var ext = "https://api.example.com/v1";
        var onlyEmbExternal = rag(true, ext, false, true, LOCAL);
        var onlyLlmExternal = rag(true, "http://localhost:11434/v1", true, false, LOCAL);

        assertThat(RagGuard.check(onlyEmbExternal, llm(LlmProperties.Client.OLLAMA, "http://localhost:11434/v1")))
                .singleElement().asString().contains("rag.embedding-base-url").contains("rag.allow-external-embedding");
        assertThat(RagGuard.check(onlyLlmExternal, llm(LlmProperties.Client.OLLAMA, ext)))
                .singleElement().asString().contains("llm.base-url").contains("rag.allow-external-llm");

        var both = rag(true, ext, true, true, LOCAL);
        assertThat(RagGuard.check(both, llm(LlmProperties.Client.OLLAMA, ext))).isEmpty();
    }

    @Test
    void 색인_역할은_LLM을_쓰지_않아_LLM_검사를_받지_않는다() {
        var r = rag(true, "http://localhost:11434/v1", false, false, LOCAL);
        var externalLlm = llm(LlmProperties.Client.OLLAMA, "https://api.example.com/v1");

        assertThat(RagGuard.check(r, externalLlm, true)).hasSize(1);
        assertThat(RagGuard.check(r, externalLlm, false)).isEmpty();
    }

    @Test
    void RAG를_꺼_두면_잘못된_RAG_설정이어도_앱이_기동한다() {
        new ApplicationContextRunner().withUserConfiguration(Cfg.class)
                .withPropertyValues("llm.base-url=http://localhost:11434/v1", "llm.model=m", "rag.enabled=false",
                        "rag.embedding-base-url=", "rag.connect-timeout-ms=0", "rag.search-deadline-ms=-1")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void 청크_크기가_문맥_상한보다_크면_켰을_때만_거부한다() {
        var on = rag(true, "http://localhost:11434/v1", false, false, LOCAL);
        var off = rag(false, "http://localhost:11434/v1", false, false, LOCAL);
        var bigChunk = new RagIndexProperties("", 2_000, 100, 0.5, 30_000, 2, true);
        var okChunk = new RagIndexProperties("", 800, 100, 0.5, 30_000, 2, true);
        var cap = new RagRetrievalProperties(3, 0.54, 1_500, 500);

        assertThat(RagGuard.checkChunking(on, bigChunk, cap)).singleElement().asString()
                .contains("rag.index.chunk-size(2000)").contains("max-context-chars(1500)");
        assertThat(RagGuard.checkChunking(on, okChunk, cap)).isEmpty();
        assertThat(RagGuard.checkChunking(off, bigChunk, cap)).as("RAG 끔이면 검사하지 않는다").isEmpty();
    }

    @Test
    void Echo_LLM은_외부_호출이_없어_LLM_검사를_건너뛴다() {
        var r = rag(true, "http://localhost:11434/v1", false, false, LOCAL);
        assertThat(RagGuard.check(r, llm(LlmProperties.Client.ECHO, "https://api.example.com/v1"))).isEmpty();
    }

    @Test
    void 켜면_모델_ID와_차원이_필수이고_검색_상한은_LLM_예산보다_작아야_한다() {
        var bad = new RagProperties(true, "http://localhost:11434/v1", "", 0, 60_000, 3_000, true, false, false, LOCAL);

        assertThat(RagGuard.check(bad, llm(LlmProperties.Client.ECHO, "http://localhost:11434/v1")))
                .anyMatch(m -> m.contains("rag.embedding-model"))
                .anyMatch(m -> m.contains("rag.embedding-dimension"))
                .anyMatch(m -> m.contains("rag.search-deadline-ms"));
    }

    @Test
    void 실제_바인딩에서_위반이면_컨텍스트가_뜨지_않고_허용하면_뜬다() {
        var runner = new ApplicationContextRunner().withUserConfiguration(Cfg.class)
                .withPropertyValues("llm.base-url=http://localhost:11434/v1", "llm.model=m", "llm.client=ollama",
                        "rag.enabled=true", "rag.embedding-model=bge-m3", "rag.embedding-dimension=1024");

        runner.withPropertyValues("rag.embedding-base-url=https://api.example.com/v1").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThatThrownBy(() -> ctx.getBean(RagGuard.class)).hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("rag.allow-external-embedding");
        });
        runner.withPropertyValues("rag.embedding-base-url=https://api.example.com/v1",
                "rag.allow-external-embedding=true").run(ctx -> assertThat(ctx).hasNotFailed());
        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** RagStartupCheck의 협력자 가짜. 임베딩이 한 번이라도 불리면 카운터가 오른다. */
    static final java.util.concurrent.atomic.AtomicInteger EMBED_CALLS = new java.util.concurrent.atomic.AtomicInteger();

    @EnableConfigurationProperties({RagProperties.class, LlmProperties.class, RagIndexProperties.class, RagRetrievalProperties.class})
    static class Collaborators {
        @org.springframework.context.annotation.Bean
        com.slack.lab.core.port.EmbeddingClient embeddingClient() {
            return (text, ms) -> {
                EMBED_CALLS.incrementAndGet();
                return new com.slack.lab.core.model.EmbeddingResult.Success(new float[1024], 1);
            };
        }

        @org.springframework.context.annotation.Bean
        com.slack.lab.core.port.VectorStore vectorStore() {
            return org.mockito.Mockito.mock(com.slack.lab.core.port.VectorStore.class);
        }
    }

    @Test
    void 유출_정책_위반이면_임베딩_프로브가_나가기_전에_기동이_거부된다_빈_정의_순서와_무관하게() {
        EMBED_CALLS.set(0);
        // 일부러 RagStartupCheck를 RagGuard보다 먼저 등록한다 — 순서가 의존으로 보장돼야 거부가 프로브보다 앞선다
        new ApplicationContextRunner().withUserConfiguration(Collaborators.class, RagStartupCheck.class, RagGuard.class)
                .withPropertyValues("llm.base-url=http://localhost:11434/v1", "llm.model=m", "llm.client=echo", "rag.enabled=true",
                        "rag.embedding-model=bge-m3", "rag.embedding-dimension=1024",
                        "rag.embedding-base-url=https://api.example.com/v1")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("rag.allow-external-embedding");
                    assertThat(EMBED_CALLS.get()).as("거부되기 전에 외부로 프로브가 나가면 안 된다").isZero();
                });
    }

    @EnableConfigurationProperties({RagProperties.class, LlmProperties.class, RagIndexProperties.class, RagRetrievalProperties.class})
    static class Cfg {
        @org.springframework.context.annotation.Bean
        RagGuard guard(RagProperties rag, LlmProperties llm, org.springframework.core.env.Environment env,
                RagIndexProperties index, RagRetrievalProperties retrieval) {
            return new RagGuard(rag, llm, env, index, retrieval);
        }
    }
}
