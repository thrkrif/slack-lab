package com.slack.lab.adapter.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.QueueProperties;
import com.slack.lab.config.RabbitProperties;
import com.slack.lab.config.WorkerProperties;
import com.slack.lab.core.service.EventProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 큐 어댑터 배선(M21). {@code queue.backend=rabbitmq}일 때만 켜진다. 기본값은 아직 Redis다 — Postgres 상태 저장소와
 * 함께 쓰는 전체 배선(재시도 릴레이, 반응 큐, 복구 CLI)은 M22에서 완성한다. 지금은 이 어댑터와 코어를 직접 이어 붙인
 * 통합 테스트({@code RabbitPipelineIT})가 동작을 검증한다.
 */
@Configuration
@ConditionalOnProperty(name = "queue.backend", havingValue = "rabbitmq")
class RabbitConfig {

    /**
     * Postgres 상태 저장소 배선은 M22에서 완성한다. 지금 이 백엔드를 켜면 Redis 상태 저장소가 RabbitMQ의 delivery tag를
     * 스트림 ID로 오해하고, 재시도는 재투입되지 않는다(조용한 실패). 개발자가 의도를 밝혀야만 켜진다.
     */
    @Bean
    RabbitWiringGuard rabbitWiringGuard(org.springframework.core.env.Environment env) {
        if (!env.getProperty("queue.rabbitmq-preview", Boolean.class, false)) {
            throw new IllegalStateException("queue.backend=rabbitmq는 M22(Postgres 상태 저장소 배선)가 끝나기 전까지 지원하지 "
                    + "않는다. 어댑터를 시험하려면 queue.rabbitmq-preview=true를 함께 준다.");
        }
        return new RabbitWiringGuard();
    }

    static final class RabbitWiringGuard {}

    @Bean(destroyMethod = "close")
    @ConditionalOnRole({AppRole.RECEIVER, AppRole.WORKER, AppRole.ALL})
    RabbitBroker rabbitBroker(RabbitProperties props, WorkerProperties worker) {
        return new RabbitBroker(props, worker.concurrency() + 1);
    }

    @Bean
    @ConditionalOnRole({AppRole.RECEIVER, AppRole.WORKER, AppRole.ALL})
    RabbitEventPublisher rabbitEventPublisher(RabbitBroker broker, ObjectMapper mapper, QueueProperties queue) {
        return new RabbitEventPublisher(broker, mapper, queue);
    }

    @Bean
    @ConditionalOnRole({AppRole.RECEIVER, AppRole.ALL})
    RabbitHealthProbe rabbitHealthProbe(RabbitBroker broker) {
        return new RabbitHealthProbe(broker);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnRole({AppRole.WORKER, AppRole.ALL})
    RabbitConsumer rabbitConsumer(RabbitBroker broker, RabbitEventPublisher publisher, EventProcessor processor,
            ObjectMapper mapper, WorkerProperties worker) {
        return new RabbitConsumer(broker, publisher, processor, mapper, worker.concurrency());
    }
}
