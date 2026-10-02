package com.slack.lab.core.port;

import com.slack.lab.core.model.SlackMessageEvent;

/**
 * 큐에서 꺼낸 메시지 하나. 코어는 브로커가 무엇인지 모르고 이 인터페이스로만 다룬다.
 *
 * <p>코어는 메시지마다 정확히 한 가지를 부른다. 처리가 끝나 확정했으면 {@link #acknowledge()}, 확정하지 못했거나 지금은
 * 처리할 수 없으면(다른 시도가 처리 중, 종료 기록 실패 등) {@link #defer()}. <b>브로커가 "ack하지 않으면 알아서 다시
 * 전달한다"는 가정은 하지 않는다</b> — Redis는 pending 회수가 그 역할을 하지만 RabbitMQ는 채널이 살아 있는 동안 ack하지 않은
 * 메시지를 다시 주지 않고 prefetch 슬롯만 차지한다. 그래서 놓아주는 방법을 어댑터가 정하도록 연산으로 둔다.
 *
 * <p>구현 지침(RabbitMQ): 같은 delivery tag를 두 번 ack하면 채널이 닫히므로 어댑터가 플래그로 멱등을 보장한다. ack는 받은
 * 원래 채널로만 하고(복구된 새 채널에 옛 tag로 ack하면 채널이 닫힌다), 이 객체의 연산은 {@code process}를 부른 스레드에서만
 * 호출된다.
 */
public interface QueueDelivery {

    SlackMessageEvent event();

    /** 메시지 세대. 최초 수신은 0이고 재시도·재처리 투입마다 커진다. */
    long gen();

    /** 최초 서버 수신 시각(UTC epoch ms). 재투입해도 바뀌지 않는다. */
    long receivedAtMs();

    /** 큐 메시지에 수신 시각이 없거나 깨져서 대체값을 쓴 경우 true. 지표 측정을 무효로 표시하는 데 쓴다. */
    default boolean receivedAtMissing() {
        return false;
    }

    /** 브로커별 불투명 전달 식별자. 상태 저장소가 종료 처리에 함께 다룰 수 있다(Redis: 스트림 항목 ID). */
    String token();

    /** 확정된 메시지를 큐에서 제거한다. 멱등이어야 한다(저장소가 이미 확인했을 수 있다). */
    void acknowledge();

    /**
     * 지금은 확정하지 않고 나중에 다시 전달받도록 놓아준다. Redis 구현은 아무것도 하지 않는다(pending에 남고 회수 주기가
     * 다시 확인한다). RabbitMQ 구현은 지연을 두고 다시 넣어야 한다 — 즉시 nack(requeue)하면 처리 중인 메시지가 빠르게
     * 맴돈다(M21).
     */
    void defer();
}
