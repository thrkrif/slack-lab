package com.slack.lab.slack;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 3초 뒤 Slack이 이미 연결을 끊은 경우 등 응답 쓰기 실패를 {@code ack_delivered=false}로만 기록한다
 * (ARCHITECTURE 리스크 표, M6). dedup 상태는 핸들러가 이미 확정한 뒤이므로 여기서 바꾸지 않는다.
 *
 * HTTP를 아는 것은 이 필터와 컨트롤러의 몫이다 — {@code event} 패키지(A13 대상)에는 두지 않는다.
 */
@Component
public class AckLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AckLoggingFilter.class);
    static final String EVENT_ID_ATTR = "slack.eventId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"/slack/events".equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        try {
            chain.doFilter(request, response);
            // 컨트롤러가 처리 대상 이벤트로 판단했을 때만 EVENT_ID_ATTR을 심는다(SlackEventController 참고) —
            // 그 외(서명 실패 401·본문 파싱 400·url_verification·무시된 type 200 등)는 event_id 자체가 없으므로
            // 상태 코드를 나열해 거르는 대신 이 값의 존재로 직접 판단한다(denylist가 새 경로를 놓치는 걸 방지).
            Object eventId = request.getAttribute(EVENT_ID_ATTR);
            if (eventId == null) {
                return;
            }
            // 필터 체인이 예외 없이 끝났어도 커밋된 응답의 실제 소켓 쓰기가 이미 끊겼을 수 있다.
            // 서블릿 API로는 이 이상 확인할 수 없어(로컬 버퍼까지만 안다), 정상 경로는 성공으로 기록한다.
            log.info("ack_delivered=true event_id={}", eventId);
        } catch (IOException e) {
            Object eventId = request.getAttribute(EVENT_ID_ATTR);
            if (eventId != null) {
                // Tomcat이 클라이언트 연결 끊김을 여기까지 전파하면(예: ClientAbortException) 응답 쓰기 실패로 본다.
                log.warn("ack_delivered=false event_id={} reason={}", eventId, e.getClass().getSimpleName());
            }
            throw e;
        }
    }
}
