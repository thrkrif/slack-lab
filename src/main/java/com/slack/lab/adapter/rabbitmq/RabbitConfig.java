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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** RabbitMQ 큐 어댑터 배선(M21~M22). 큐 백엔드는 RabbitMQ 하나뿐이다(ADR-9). */
@Configuration
class RabbitConfig {

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
    @ConditionalOnRole({AppRole.WORKER, AppRole.ALL})
    RabbitBacklogProbe rabbitBacklogProbe(RabbitBroker broker) {
        return new RabbitBacklogProbe(broker);
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
