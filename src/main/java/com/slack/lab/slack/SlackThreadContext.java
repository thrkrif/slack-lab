package com.slack.lab.slack;

import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.event.SlackMessageEvent;
import com.slack.lab.event.ThreadContextSource;
import com.slack.lab.llm.LlmMessage;
import com.slack.lab.slack.SlackThreadClient.FetchResult;
import com.slack.lab.slack.SlackThreadClient.ThreadMessage;
import com.slack.lab.slack.SlackThreadClient.Identity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code conversations.replies}로 스레드의 이전 대화를 읽어 LLM 문맥으로 조립한다(M16). 봇 메시지는 assistant,
 * 사람 메시지는 user 역할이고 이번 메시지는 뺀다(중복 방지). 실패·시간 초과는 문맥 없이 진행한다.
 * 프롬프트와 본문은 로그에 남기지 않고 메시지 수와 글자 수만 기록한다.
 */
@Component
@ConditionalOnRole({AppRole.WORKER, AppRole.ALL})
public class SlackThreadContext implements ThreadContextSource {

    private static final Logger log = LoggerFactory.getLogger(SlackThreadContext.class);

    private final SlackThreadClient client;
    private final ContextProperties props;

    public SlackThreadContext(SlackThreadClient client, ContextProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public List<LlmMessage> fetch(SlackMessageEvent event, long budgetMs) {
        long deadline = Math.min(props.fetchDeadlineMs(), budgetMs);
        if (deadline <= 0) {
            log.warn("스레드 문맥 조회 예산 없음 — 문맥 없이 진행 event_id={}", event.eventId());
            return List.of();
        }
        long start = System.nanoTime();
        FetchResult result;
        try {
            result = client.fetchMessages(event.channel(), event.threadTs(), deadline);
        } catch (RuntimeException e) {
            log.warn("스레드 문맥 조회 예외 — 문맥 없이 진행 event_id={} reason={}", event.eventId(),
                    e.getClass().getSimpleName());
            return List.of();
        }
        long fetchMs = (System.nanoTime() - start) / 1_000_000;
        if (result.failed() || !result.complete()) {
            log.warn("스레드 문맥 조회 실패 — 문맥 없이 진행 event_id={} reason={} fetch_ms={}", event.eventId(),
                    result.error(), fetchMs);
            return List.of();
        }
        // 식별은 캐시되므로 첫 호출에만 비용이 든다. 실패하면 null이고, 그 경우 봇 메시지는 모두 assistant로 본다.
        Identity self = client.identity(Math.max(1, deadline - fetchMs));
        List<LlmMessage> context = assemble(result.messages(), event, self);
        int chars = context.stream().mapToInt(m -> m.content().length()).sum();
        log.info("스레드 문맥 event_id={} context_messages={} context_chars={} fetch_ms={}", event.eventId(),
                context.size(), chars, fetchMs);
        return context;
    }

    /** 이번 메시지와 빈 메시지를 빼고, 최근 {@code max-messages}개 안에서 {@code max-chars}를 넘지 않게 오래된 것부터 버린다. */
    List<LlmMessage> assemble(List<ThreadMessage> messages, SlackMessageEvent event, Identity self) {
        String botUserId = self != null && !self.userId().isBlank() ? self.userId() : event.botUserId();
        BigDecimal current = parseTs(event.ts());
        List<LlmMessage> all = new ArrayList<>();
        for (ThreadMessage m : messages) {
            // 이번 메시지와 그 뒤에 올라온 메시지는 "이전 대화"가 아니다. conversations.replies는 조회 시점의
            // 스레드 전체를 주므로(큐 지연·재시도 사이에 쌓임) 시각 순서로 걸러야 한다. ts는 소수 문자열이라
            // 문자열 비교가 아니라 숫자로 비교한다.
            BigDecimal ts = parseTs(m.ts());
            if (m.ts().equals(event.ts()) || (current != null && ts != null && ts.compareTo(current) >= 0)) {
                continue;
            }
            String text = SlackMessageEvent.cleanText(m.text(), botUserId);
            if (text.isBlank()) {
                continue;
            }
            if (m.botId().isBlank()) {
                all.add(LlmMessage.user(text));
            } else if (self == null || self.botId().isBlank() || self.botId().equals(m.botId())) {
                all.add(LlmMessage.assistant(text));
            }
            // 다른 앱의 봇 메시지는 버린다: assistant로 넣으면 모델이 자기 말로 믿는 주입 경로가 된다.
        }
        int from = Math.max(0, all.size() - props.maxMessages());
        List<LlmMessage> recent = all.subList(from, all.size());

        // 가장 최근 것부터 담고 한도를 넘으면 멈춘다. 가장 최근 한 건이 혼자 한도를 넘으면 잘라서라도 넣는다.
        List<LlmMessage> picked = new ArrayList<>();
        int total = 0;
        for (int i = recent.size() - 1; i >= 0; i--) {
            LlmMessage m = recent.get(i);
            int len = m.content().length();
            if (total + len > props.maxChars()) {
                if (picked.isEmpty()) {
                    picked.add(new LlmMessage(m.role(), m.content().substring(m.content().length() - props.maxChars())));
                }
                break;
            }
            picked.add(m);
            total += len;
        }
        Collections.reverse(picked);
        return picked;
    }

    /** Slack ts("1700000000.000100")를 숫자로. 파싱할 수 없으면 null. */
    private static BigDecimal parseTs(String ts) {
        try {
            return new BigDecimal(ts);
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }
}
