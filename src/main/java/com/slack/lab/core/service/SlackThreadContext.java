package com.slack.lab.core.service;

import com.slack.lab.core.model.BotIdentity;
import com.slack.lab.core.model.FetchResult;
import com.slack.lab.core.port.ThreadLookup;
import com.slack.lab.core.model.ThreadMessage;
import com.slack.lab.config.ContextProperties;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.core.model.SlackMessageEvent;
import com.slack.lab.core.port.ThreadContextSource;
import com.slack.lab.core.model.LlmMessage;
import com.slack.lab.core.model.ReplyFooter;
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

    private final ThreadLookup client;
    private final ContextProperties props;

    public SlackThreadContext(ThreadLookup client, ContextProperties props) {
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
        // 식별은 캐시되므로 첫 호출에만 비용이 든다. 실패하면 null이고, 그 경우 이벤트(서명 검증된 페이로드의 authorizations)가 알려 주는
        // 우리 봇 사용자 ID로만 우리 봇 메시지를 판별한다 — 판별할 수 없는 봇 메시지는 모델에 넘기지 않는다(fail-closed).
        BotIdentity self = client.identity(Math.max(1, deadline - fetchMs));
        List<LlmMessage> context = assemble(result.messages(), event, self);
        int chars = context.stream().mapToInt(m -> m.content().length()).sum();
        log.info("스레드 문맥 event_id={} context_messages={} context_chars={} fetch_ms={}", event.eventId(),
                context.size(), chars, fetchMs);
        return context;
    }

    /** 이번 메시지와 빈 메시지를 빼고, 최근 {@code max-messages}개 안에서 {@code max-chars}를 넘지 않게 오래된 것부터 버린다. */
    List<LlmMessage> assemble(List<ThreadMessage> messages, SlackMessageEvent event, BotIdentity self) {
        String botUserId = self != null && !self.userId().isBlank() ? self.userId() : event.botUserId();
        BigDecimal current = parseTs(event.ts());
        List<LlmMessage> all = new ArrayList<>();
        int unidentifiedBots = 0;
        for (ThreadMessage m : messages) {
            // 이번 메시지와 그 뒤에 올라온 메시지는 "이전 대화"가 아니다. conversations.replies는 조회 시점의
            // 스레드 전체를 주므로(큐 지연·재시도 사이에 쌓임) 시각 순서로 걸러야 한다. ts는 소수 문자열이라
            // 문자열 비교가 아니라 숫자로 비교한다.
            BigDecimal ts = parseTs(m.ts());
            if (m.ts().equals(event.ts()) || (current != null && ts != null && ts.compareTo(current) >= 0)) {
                continue;
            }
            // 서버가 붙인 참고 문서·검색 안내는 모델이 쓴 말이 아니다. cleanText가 줄바꿈을 접기 전에(표지가 줄 단위다) 걷어내지
            // 않으면 모델이 그 서식을 흉내 내 본문에 가짜 출처를 쓴다.
            String raw = m.botId().isBlank() ? m.text() : ReplyFooter.stripFooter(m.text());
            String text = SlackMessageEvent.cleanText(raw, botUserId);
            if (text.isBlank()) {
                continue;
            }
            if (m.botId().isBlank()) {
                all.add(LlmMessage.user(text));
            } else if (isOurBot(m, self, botUserId)) {
                all.add(LlmMessage.assistant(text));
            } else {
                // 다른 앱의 봇 메시지이거나 누구 것인지 판별할 수 없는 봇 메시지는 버린다: assistant로 넣으면 모델이 자기 말로 믿는
                // 주입 경로가 된다.
                unidentifiedBots++;
            }
        }
        if (self == null && unidentifiedBots > 0) {
            log.warn("봇 신원 조회 실패 — 이벤트의 봇 식별 정보로 판별되지 않은 봇 메시지 {}건을 문맥에서 제외 event_id={}", unidentifiedBots,
                    event.eventId());
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

    /**
     * 우리 봇이 쓴 메시지인가. 봇 ID(bot_id)를 알면 그것으로, 모르면(식별 조회 실패·ID 없음) 이벤트가 준 우리 봇 사용자 ID가 메시지의
     * {@code user}와 같을 때만 그렇다고 본다. 둘 다 없으면 판별 불가이므로 아니다 — "모르면 우리 것"으로 단정하지 않는다.
     */
    static boolean isOurBot(ThreadMessage m, BotIdentity self, String botUserId) {
        if (self != null && !self.botId().isBlank()) {
            return self.botId().equals(m.botId());
        }
        return botUserId != null && !botUserId.isBlank() && botUserId.equals(m.user());
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
