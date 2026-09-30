package com.slack.lab.queue;

import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.event.SlackMessageEvent;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.IntegerListOutput;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 수신 이벤트를 큐에 내구성 있게 저장한다(P1-1). {@code XADD} 뒤 {@code WAITAOF 1 0 <ms>}로 로컬 fsync를
 * 확인해야 200을 줄 수 있다(PLAN 2단계 M9 실측 — p95 3.18ms, ARCHITECTURE ADR-8). Spring Data Redis에는
 * {@code WAITAOF} 전용 API가 없어 {@link StringRedisTemplate#execute(String, byte[]...)}의 raw 명령
 * 경로로 보낸다. 이 경로는 값 직렬화를 타지 않는 로우레벨 호출이라, 인자를 직접 바이트로 만든다.
 */
@Component
@ConditionalOnRole({AppRole.RECEIVER, AppRole.ALL})
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    private static final String SCHEMA_VERSION = "1";

    // 두 스트림에 한 번에 XADD하는 스크립트(M15). 반환은 이벤트 스트림 항목 ID다.
    private static final RedisScript<String> PUBLISH = publishScript();

    private final StringRedisTemplate redis;
    private final QueueProperties props;
    private final ReactionProperties reaction;

    public EventPublisher(StringRedisTemplate redis, QueueProperties props, ReactionProperties reaction) {
        this.redis = redis;
        this.props = props;
        this.reaction = reaction;
    }

    private static RedisScript<String> publishScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("queue/publish.lua"));
        script.setResultType(String.class);
        return script;
    }

    /** 첫 발행은 gen 0이다. 재시도·재처리 투입은 M13·M14의 몫이라 이 클래스는 모른다. */
    public PublishResult publish(SlackMessageEvent event, long receivedAtMs, String retryNum) {
        long start = System.nanoTime();
        String id;
        try {
            id = redis.execute(PUBLISH, List.of(props.streamKey(), reaction.streamKey()), args(event, receivedAtMs, retryNum));
            if (id == null) {
                throw new IllegalStateException("발행 스크립트가 ID를 돌려주지 않음");
            }
        } catch (RuntimeException e) {
            log.warn("큐 저장 실패 event_id={} reason={}", event.eventId(), e.getClass().getSimpleName());
            return new PublishResult.Failed("enqueue_failed:" + e.getClass().getSimpleName());
        }

        try {
            if (!confirmDurable()) {
                log.warn("큐 저장 확인 실패(numlocal<1) event_id={} stream_id={}", event.eventId(), id);
                return new PublishResult.Unconfirmed("waitaof_numlocal_0");
            }
        } catch (RuntimeException e) {
            // XADD는 됐을 수 있으나 확인을 못 받았다 — 저장 여부가 불명확하므로 200을 주지 않는다.
            log.warn("큐 저장 확인 실패 event_id={} stream_id={} reason={}", event.eventId(), id,
                    e.getClass().getSimpleName());
            return new PublishResult.Unconfirmed("waitaof_failed:" + e.getClass().getSimpleName());
        }

        long enqueueMs = (System.nanoTime() - start) / 1_000_000;
        log.info("큐 저장 확인 event_id={} stream_id={} enqueue_ms={}", event.eventId(), id, enqueueMs);
        return new PublishResult.Enqueued(id);
    }

    /** 이벤트 필드 인자 수, 이벤트 필드/값들, 반응 필드/값들 순서(publish.lua). 반응 항목에는 본문이 없다. */
    private Object[] args(SlackMessageEvent event, long receivedAtMs, String retryNum) {
        Map<String, String> ev = fields(event, receivedAtMs, retryNum);
        Map<String, String> rx = new LinkedHashMap<>();
        rx.put("event_id", event.eventId());
        rx.put("channel", event.channel());
        rx.put("ts", event.ts());
        rx.put("received_at", String.valueOf(receivedAtMs));
        List<Object> out = new java.util.ArrayList<>();
        out.add(String.valueOf(ev.size() * 2));
        ev.forEach((k, v) -> {
            out.add(k);
            out.add(v);
        });
        rx.forEach((k, v) -> {
            out.add(k);
            out.add(v);
        });
        return out.toArray();
    }

    private Map<String, String> fields(SlackMessageEvent event, long receivedAtMs, String retryNum) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("schema_version", SCHEMA_VERSION);
        f.put("event_id", event.eventId());
        f.put("gen", "0");
        f.put("received_at", String.valueOf(receivedAtMs));
        f.put("channel", event.channel());
        f.put("ts", event.ts());
        f.put("thread_ts", nz(event.threadTs()));
        f.put("user", nz(event.user()));
        f.put("text", nz(event.text()));
        f.put("retry_num", nz(retryNum));
        return f;
    }

    /**
     * {@code WAITAOF 1 0 <timeout>}. 반환은 {@code [numlocal, numreplicas]} — numlocal>=1이면 로컬 fsync 확인이다.
     *
     * <p>이 응답은 정수 배열이라 {@code RedisConnection.execute(String, byte[]...)}의 기본 출력 파서
     * ({@code ByteArrayOutput})로는 디코딩할 수 없다({@code UnsupportedOperationException: ByteArrayOutput
     * does not support set(long)}, 실측). 그래서 Lettuce의 {@link IntegerListOutput}을 직접 지정해 호출한다.
     * {@code StringRedisTemplate.execute(RedisCallback)}가 건네는 연결은 프록시라 {@link LettuceConnection}으로
     * 캐스트할 수 없어, 커넥션 팩토리에서 원본 연결을 직접 꺼내 쓰고 명시적으로 닫는다.
     */
    private boolean confirmDurable() {
        RedisConnection connection = redis.getConnectionFactory().getConnection();
        try {
            LettuceConnection lettuce = (LettuceConnection) connection;
            Object raw = lettuce.execute("WAITAOF", new IntegerListOutput<>(ByteArrayCodec.INSTANCE),
                    bytes("1"), bytes("0"), bytes(String.valueOf(props.enqueueTimeoutMs())));
            return numLocal(raw) >= 1;
        } finally {
            connection.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static long numLocal(Object raw) {
        if (raw instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Number n) {
                return n.longValue();
            }
        }
        throw new IllegalStateException("WAITAOF 응답 형식 예상과 다름: " + raw);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
