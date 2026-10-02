package com.slack.lab.adapter.rabbitmq;

import com.slack.lab.core.port.ChatNotifier;
import com.slack.lab.config.ReactionProperties;
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
 * RabbitMQ 큐 어댑터 배선(M21~M22). {@code queue.backend=rabbitmq}일 때만 켜지고, Postgres 상태 저장소
 * ({@code state.backend=postgres})와 함께 써야 한다. 기본값은 아직 Redis다(M22-2에서 Redis를 제거한다).
 */
@Configuration
@ConditionalOnProperty(name = "queue.backend", havingValue = "rabbitmq")
class RabbitConfig {

    /**
     * Redis 상태 저장소는 RabbitMQ의 delivery tag를 스트림 ID로 오해하고, 재시도는 재투입되지 않는다(조용한 실패). 한쪽만 바꾼
     * 반쯤 배선된 앱이 뜨지 않게 Postgres 상태 저장소와 함께 쓰도록 강제한다.
     */
    @Bean
    RabbitWiringGuard rabbitWiringGuard(org.springframework.core.env.Environment env) {
        if (!"postgres".equals(env.getProperty("state.backend"))) {
            throw new IllegalStateException("queue.backend=rabbitmq는 state.backend=postgres와 함께 써야 한다.");
        }
        return new RabbitWiringGuard();
    }

    static final class RabbitWiringGuard {}

    @Bean(destroyMethod = "close")
    @ConditionalOnRole({AppRole.RECEIVER, AppRole.WORKER, AppRole.REACTOR, AppRole.ALL})
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

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnRole({AppRole.REACTOR, AppRole.WORKER, AppRole.ALL})
    RabbitReactionConsumer rabbitReactionConsumer(RabbitBroker broker, ObjectMapper mapper,
            com.slack.lab.core.port.ChatNotifier notifier, com.slack.lab.config.ReactionProperties reaction) {
        return new RabbitReactionConsumer(broker, mapper, notifier, reaction);
    }
}
