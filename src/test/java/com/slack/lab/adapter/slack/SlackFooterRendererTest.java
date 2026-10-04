package com.slack.lab.adapter.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.core.model.DocumentHit;
import com.slack.lab.core.model.ReferenceList;
import com.slack.lab.core.model.ReplyFooter;
import com.slack.lab.core.model.ReplyMetadata;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlackFooterRendererTest {

    static ReplyFooter refs(DocumentHit... hits) {
        return new ReplyFooter.References(ReferenceList.fromInjected(List.of(hits)));
    }

    @Test
    void 덧붙임이_없으면_본문_그대로다() {
        assertThat(SlackFooterRenderer.render("답변", ReplyFooter.NONE)).isEqualTo("답변");
        assertThat(SlackFooterRenderer.render("답변", new ReplyFooter.References(new ReferenceList(List.of())))).isEqualTo("답변");
    }

    @Test
    void 참고_문서는_문서_ID_기준으로_한_번씩_ID와_제목만_보여준다() {
        String out = SlackFooterRenderer.render("답변", refs(new DocumentHit("DB-003", "커넥션 풀 고갈", "원문은 안 나간다", 0.9),
                new DocumentHit("OPS-012", "점검 절차", "x", 0.8), new DocumentHit("DB-003", "커넥션 풀 고갈", "또 다른 조각", 0.7)));

        assertThat(out).isEqualTo("답변\n\n*참고 문서*\n• [DB-003] 커넥션 풀 고갈\n• [OPS-012] 점검 절차");
        assertThat(out).doesNotContain("원문은 안 나간다");
    }

    @Test
    void 제목의_멘션_링크_서식은_Slack이_해석하지_못하게_이스케이프한다() {
        String out = SlackFooterRenderer.render("답변", refs(new DocumentHit("X-1", "<!channel> <@U123|admin> <https://evil.example|클릭> *굵게*",
                "t", 0.9)));

        assertThat(out).contains("&lt;!channel&gt;").contains("&lt;@U123|admin&gt;").contains("&lt;https://evil.example|클릭&gt;");
        assertThat(out).doesNotContain("<!channel>").doesNotContain("<@U123").doesNotContain("<https").doesNotContain("*굵게*");
    }

    @Test
    void 제목의_줄바꿈_제어문자는_한_줄로_펴고_긴_제목은_자른다() {
        String out = SlackFooterRenderer.render("답변", refs(new DocumentHit("X", "첫 줄\n둘째 줄\r\n\u0007" + "가".repeat(300), "t", 0.9)));

        String[] lines = out.split("\n");
        assertThat(lines).hasSize(4); // 본문, 빈 줄, 헤더, 항목 한 줄
        assertThat(lines[3]).startsWith("• [X] 첫 줄 둘째 줄").endsWith("…").hasSizeLessThan(160);
    }

    @Test
    void 긴_제목은_이모지_서로게이트_쌍_중간에서_자르지_않는다() {
        String out = SlackFooterRenderer.render("답변", refs(new DocumentHit("X", "😀".repeat(200), "t", 0.9)));

        String item = out.split("\n")[3];
        assertThat(item).endsWith("…");
        assertThat(item.chars().filter(c -> Character.isHighSurrogate((char) c)).count())
                .isEqualTo(item.chars().filter(c -> Character.isLowSurrogate((char) c)).count());
    }

    @Test
    void 안내_문구는_검색_장애와_관련_문서_없음을_구분한다() {
        String none = SlackFooterRenderer.render("답변", new ReplyFooter.NoRelevantDocuments());
        String down = SlackFooterRenderer.render("답변", new ReplyFooter.SearchUnavailable());

        assertThat(none).contains("관련 문서 근거를 찾지 못해");
        assertThat(down).contains("문서 검색을 완료하지 못해").contains("내부 운영 절차와 다를 수 있습니다");
        assertThat(none).isNotEqualTo(down);
    }

    @Test
    void 답변_본문의_채널_멘션_사용자_멘션은_알림이_나가지_않게_이스케이프한다() {
        String out = SlackFooterRenderer.render("<!channel> 전원 확인 <@U123> <#C456|general> <https://ok.example|링크>",
                ReplyFooter.NONE);

        assertThat(out).startsWith("&lt;!channel&gt;".substring(0, 8)).doesNotContain("<!channel>").doesNotContain("<@U123>")
                .doesNotContain("<#C456").contains("<https://ok.example|링크>");
    }

    @Test
    void 렌더링한_덧붙임은_문맥용_stripFooter로_정확히_되돌릴_수_있다() {
        for (ReplyFooter f : new ReplyFooter[] {refs(new DocumentHit("A", "제목", "t", 0.9)),
                new ReplyFooter.NoRelevantDocuments(), new ReplyFooter.SearchUnavailable()}) {
            assertThat(ReplyFooter.stripFooter(SlackFooterRenderer.render("본문", f))).isEqualTo("본문");
        }
        assertThat(ReplyFooter.stripFooter("덧붙임 없는 본문")).isEqualTo("덧붙임 없는 본문");
    }

    @Test
    void SlackClient가_렌더링한_본문을_chat_postMessage로_보낸다() throws Exception {
        try (var stub = StubSlackServer.respondsWith("{\"ok\":true,\"ts\":\"100.1\"}", 200)) {
            var client = new SlackClient(new com.slack.lab.config.SlackProperties("secret", "xoxb-test", stub.baseUrl(), 10_000),
                    new ObjectMapper());

            client.postMessage("C1", "99.9", "답변", refs(new DocumentHit("DB-003", "커넥션 풀", "t", 0.9)), 5_000,
                    new ReplyMetadata("Ev1", "att-1"));

            var body = new ObjectMapper().readTree(stub.lastBody());
            assertThat(body.path("text").asText()).isEqualTo("답변\n\n*참고 문서*\n• [DB-003] 커넥션 풀");
            assertThat(body.path("metadata").path("event_payload").path("event_id").asText()).isEqualTo("Ev1");
        }
    }
}
