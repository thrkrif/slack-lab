package com.slack.lab.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 실제 Slack 워크스페이스에 발신하는 수동 통합 테스트다. `SLACK_MANUAL_TEST_CHANNEL` 환경변수가 없으면
 * 건너뛴다 — 그래서 평소 {@code ./gradlew build}는 실제 채널에 메시지를 보내지 않는다.
 *
 * 실행: {@code SLACK_MANUAL_TEST_CHANNEL=<채널ID> ./gradlew test --tests SlackClientManualIT}
 * (.env의 SLACK_BOT_TOKEN도 함께 로드되어 있어야 한다)
 */
class SlackClientManualIT {

    @Test
    void 실제_채널에_스레드_답글을_보낸다() {
        // codex 리뷰 지적: threadTs=null이면 최상위 메시지만 검증하고 "스레드 답글"(A1이 요구하는 형태)은
        // 검증하지 않는다. 부모 메시지를 먼저 보내 ts를 얻고, 그 ts로 실제 스레드 답글을 보낸다.
        String channel = System.getenv("SLACK_MANUAL_TEST_CHANNEL");
        assumeTrue(channel != null && !channel.isBlank(), "SLACK_MANUAL_TEST_CHANNEL 미설정 — 건너뜀");
        String token = System.getenv("SLACK_BOT_TOKEN");
        assumeTrue(token != null && !token.isBlank(), "SLACK_BOT_TOKEN 미설정 — 건너뜀");

        var props = new SlackProperties("unused", token, "https://slack.com/api", 10_000);
        var client = new SlackClient(props, new ObjectMapper());

        var parent = client.postMessage(channel, null, "slack-lab M5 수동 검증 — 부모 메시지 (자동 삭제되지 않음)", 10_000);
        assertThat(parent).isInstanceOf(SlackSendResult.Success.class);
        String parentTs = ((SlackSendResult.Success) parent).ts();

        var reply = client.postMessage(channel, parentTs, "slack-lab M5 수동 검증 — 스레드 답글", 10_000);
        assertThat(reply).isInstanceOf(SlackSendResult.Success.class);
    }

    @Test
    void 잘못된_채널은_명확한_실패로_분류된다() {
        // 이 테스트도 수동 실행 플래그로 묶는다 — 그래야 .env를 로드한 평소 build가 매번 실제 Slack API를 부르지 않는다.
        String channel = System.getenv("SLACK_MANUAL_TEST_CHANNEL");
        assumeTrue(channel != null && !channel.isBlank(), "SLACK_MANUAL_TEST_CHANNEL 미설정 — 건너뜀");
        String token = System.getenv("SLACK_BOT_TOKEN");
        assumeTrue(token != null && !token.isBlank(), "SLACK_BOT_TOKEN 미설정 — 건너뜀");

        var props = new SlackProperties("unused", token, "https://slack.com/api", 10_000);
        var client = new SlackClient(props, new ObjectMapper());

        var result = client.postMessage("C000000000", null, "이 메시지는 도달하지 않아야 한다", 10_000);
        assertThat(result).isEqualTo(new SlackSendResult.Failed("channel_not_found"));
    }
}
