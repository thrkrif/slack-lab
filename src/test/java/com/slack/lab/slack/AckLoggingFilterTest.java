package com.slack.lab.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link AckLoggingFilter}는 0건이었던 테스트를 채운다(code-reviewer MEDIUM 지적).
 * ack_delivered 로그 자체가 산출물(P0-7)이라 실제 로그 라인을 캡처해 검증한다 — 상태 코드만 보고 통과시키면
 * "401/400엔 로그 생략" 요구가 조용히 깨져도 잡지 못한다.
 */
class AckLoggingFilterTest {

    private final AckLoggingFilter filter = new AckLoggingFilter();
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(AckLoggingFilter.class)).addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(AckLoggingFilter.class)).detachAppender(appender);
    }

    private HttpServletRequest requestFor(String uri, String eventId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getAttribute("slack.eventId")).thenReturn(eventId);
        return request;
    }

    @Test
    void 정상_처리면_ack_delivered_true를_기록한다() throws Exception {
        HttpServletRequest request = requestFor("/slack/events", "Ev1");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("ack_delivered=true event_id=Ev1"));
    }

    @Test
    void event_id가_없으면_상태코드와_무관하게_ack_delivered를_기록하지_않는다() throws Exception {
        // 처리 대상 이벤트로 판단된 요청만 EVENT_ID_ATTR이 심긴다(SlackEventController) — 서명 실패 401,
        // 본문 파싱 400뿐 아니라 url_verification 200, 무시된 type 200도 전부 이 경로로 걸러져야 한다.
        // 상태 코드를 나열해 거르면(예: 400·401만) 이런 200 응답 경로가 새서 지표가 오염된다.
        HttpServletRequest request = requestFor("/slack/events", null);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("ack_delivered"));
    }

    @Test
    void 응답_쓰기_중_IO예외는_ack_delivered_false를_기록하고_다시_던진다() throws Exception {
        // 3초 뒤 Slack이 연결을 끊어 응답 쓰기가 실패하는 경우(ARCHITECTURE 리스크 표)를 재현한다.
        HttpServletRequest request = requestFor("/slack/events", "Ev1");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        doThrow(new IOException("클라이언트 연결 끊김")).when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isInstanceOf(IOException.class);

        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("ack_delivered=false event_id=Ev1"));
    }

    @Test
    void event_id_없이_응답_쓰기_중_IO예외가_나도_ack_delivered를_기록하지_않는다() throws Exception {
        // 처리 대상이 아니었던 요청(예: challenge 응답 쓰기 실패)까지 ack_delivered=false를 남기지 않는다.
        HttpServletRequest request = requestFor("/slack/events", null);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        doThrow(new IOException("클라이언트 연결 끊김")).when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isInstanceOf(IOException.class);

        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("ack_delivered"));
    }

    @Test
    void slack_events가_아닌_경로는_그대로_통과시키고_아무_것도_기록하지_않는다() throws Exception {
        HttpServletRequest request = requestFor("/health", null);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(appender.list).isEmpty();
    }
}
