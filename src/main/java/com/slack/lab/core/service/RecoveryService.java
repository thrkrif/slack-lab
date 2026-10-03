package com.slack.lab.core.service;

import com.slack.lab.core.port.RecoveryStore;
import com.slack.lab.core.model.ScanResult;
import com.slack.lab.core.port.ThreadLookup;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.core.model.RecoveryOutcome;
import com.slack.lab.core.port.RecoveryStore.Entry;
import com.slack.lab.core.port.RecoveryStore.ThreadRef;
import java.io.PrintStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 결과 불명·DLQ 건을 사람이 다루는 명령들(M14). <b>자동 재발신 경로는 없다</b> — 전송 여부를 사람이 확인한 뒤
 * {@code resolve-completed}·{@code reprocess}·{@code close} 중 하나를 고른다. 출력에 입력 본문은 담지 않는다.
 */
@Component
@ConditionalOnRole(AppRole.RECOVERY)
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);
    // `--`로 시작하면 Boot가 옵션으로 파싱해 비옵션 인자에서 빠지므로 위치 인자로 받는다.
    static final String CONFIRM_FLAG = "confirm-unsent";

    static final String USAGE = """
            사용법: recovery <명령>
              list                                   DLQ·복구 목록의 미해결 건
              check <event_id>                       스레드에서 봇 답글(metadata)을 찾는다
              resolve-completed <event_id> <ts>      답글이 있었음을 확인 → COMPLETED
              reprocess <event_id> confirm-unsent  미전송을 확인한 건에 1회 재실행 승인
              close <event_id>                       더 처리하지 않고 닫는다""";

    private final RecoveryStore store;
    private final ThreadLookup threads;

    public RecoveryService(RecoveryStore store, ThreadLookup threads) {
        this.store = store;
        this.threads = threads;
    }

    /** @return 프로세스 종료 코드(0 성공, 1 실패·거절, 2 사용법 오류) */
    public int execute(List<String> args, PrintStream out) {
        if (args.isEmpty()) {
            out.println(USAGE);
            return 2;
        }
        String cmd = args.get(0);
        return switch (cmd) {
            case "list" -> list(out);
            case "check" -> args.size() == 2 ? check(args.get(1), out) : usage(out);
            case "resolve-completed" -> args.size() == 3 ? resolveCompleted(args.get(1), args.get(2), out) : usage(out);
            case "reprocess" -> args.size() == 3 && CONFIRM_FLAG.equals(args.get(2))
                    ? reprocess(args.get(1), out) : reprocessUsage(out);
            case "close" -> args.size() == 2 ? close(args.get(1), out) : usage(out);
            default -> usage(out);
        };
    }

    private int usage(PrintStream out) {
        out.println(USAGE);
        return 2;
    }

    private int reprocessUsage(PrintStream out) {
        out.println("reprocess는 답글이 스레드에 없음을 `check`로 확인한 건에만 실행한다. 확인했다면 "
                + CONFIRM_FLAG + "를 덧붙인다.");
        out.println("재발신은 이미 답글이 있으면 중복 답글이 된다 — 자동 경로가 없는 이유다.");
        return 2;
    }

    private int list(PrintStream out) {
        List<Entry> entries = store.list();
        if (entries.isEmpty()) {
            out.println("미해결 건 없음");
            return 0;
        }
        out.printf("%-9s %-8s %-8s %-28s %-6s %-26s %s%n", "LIST", "STATE", "STAGE", "ERROR", "GEN", "PRESERVED_AT",
                "EVENT_ID");
        for (Entry e : entries) {
            Map<String, String> st = e.state();
            out.printf("%-9s %-8s %-8s %-28s %-6s %-26s %s%n", e.list(), st.getOrDefault("state", "-"),
                    st.getOrDefault("stage", "-"), errorOf(st, e.reason()), st.getOrDefault("gen", "-"),
                    Instant.ofEpochMilli(e.preservedAtMs()), e.eventId());
        }
        out.println("합계 " + entries.size() + "건");
        return 0;
    }

    /** 오류 코드와 외부 상세를 한 칸으로 보인다. 기록이 없으면 대체 값. */
    private static String errorOf(Map<String, String> st, String fallback) {
        String code = st.get("error_code");
        if (code == null || code.isBlank()) {
            return fallback;
        }
        String detail = st.get("error_detail");
        return detail == null || detail.isBlank() ? code : code + ":" + detail;
    }

    private int check(String eventId, PrintStream out) {
        Map<String, String> st = store.stateOf(eventId);
        if (st.isEmpty()) {
            out.println("상태 기록이 없다: event_id 확인");
            return 1;
        }
        out.println("상태=" + st.getOrDefault("state", "-") + " stage=" + st.getOrDefault("stage", "-")
                + " error=" + errorOf(st, "-") + " gen=" + st.getOrDefault("gen", "-"));
        String state = st.getOrDefault("state", "");
        if ("COMPLETED".equals(state) || "CLOSED".equals(state)) {
            // 해결되면 보존본(스레드 위치를 담은 입력)이 지워져 조회할 수도, 할 필요도 없다.
            out.println("이미 해결된 건이다 — 할 일 없음");
            return 0;
        }
        Optional<ThreadRef> ref = store.threadRef(eventId);
        if (ref.isEmpty()) {
            out.println("스레드 위치(channel·ts)를 알 수 없어 조회할 수 없다");
            return 1;
        }
        ScanResult scan = threads.findReplies(ref.get().channel(), ref.get().rootTs(), eventId);
        if (scan.failed()) {
            out.println("스레드 조회 실패: " + scan.error() + " — 판단할 수 없다(스코프 channels:history 확인)");
            return 1;
        }
        if (!scan.matches().isEmpty()) {
            scan.matches().forEach(m -> out.println("답글 발견 ts=" + m.ts() + " attempt_id=" + m.attemptId()));
            out.println("→ 전송됨. 완료 처리: resolve-completed " + eventId + " <ts>");
            return 0;
        }
        if (!scan.complete()) {
            out.println("답글을 찾지 못했지만 스레드를 끝까지 읽지 못했다 — 없다고 단정할 수 없다");
            return 1;
        }
        out.println("답글 없음(스레드 전체 확인) → 미전송으로 볼 수 있다. 재처리: reprocess " + eventId + " " + CONFIRM_FLAG);
        return 0;
    }

    private int resolveCompleted(String eventId, String ts, PrintStream out) {
        return report("resolve-completed", eventId, store.resolveCompleted(eventId, ts), out);
    }

    private int reprocess(String eventId, PrintStream out) {
        return report("reprocess", eventId, store.reprocess(eventId), out);
    }

    private int close(String eventId, PrintStream out) {
        return report("close", eventId, store.close(eventId), out);
    }

    private int report(String command, String eventId, RecoveryOutcome outcome, PrintStream out) {
        // 사람의 복구 조치는 나중에 추적할 수 있게 로그에도 남긴다(event_id·명령·결과만).
        log.info("복구 조치 command={} event_id={} status={}", command, eventId, outcome.status());
        out.println(command + ": " + outcome.describe());
        return outcome.ok() ? 0 : 1;
    }
}
