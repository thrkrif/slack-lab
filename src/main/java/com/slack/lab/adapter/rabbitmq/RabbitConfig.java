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
            ObjectMapper mapper, WorkerProperties worker,
            org.springframework.beans.factory.ObjectProvider<com.slack.lab.config.RagStartupCheck> ragStartupCheck) {
        // RAG를 켰다면 기동 확인(모델·차원·색인 메타)이 끝나 거부 여부가 정해진 뒤에 소비를 시작한다. 거부할 기동이 그 전에
        // 메시지를 처리·발신하면 안 된다. RAG가 꺼져 있으면 빈이 없어 아무 일도 하지 않는다.
        ragStartupCheck.getIfAvailable();
        return new RabbitConsumer(broker, publisher, processor, mapper, worker.concurrency());
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnRole({AppRole.REACTOR, AppRole.WORKER, AppRole.ALL})
    RabbitReactionConsumer rabbitReactionConsumer(RabbitBroker broker, ObjectMapper mapper,
            com.slack.lab.core.port.ChatNotifier notifier, com.slack.lab.config.ReactionProperties reaction,
            org.springframework.beans.factory.ObjectProvider<com.slack.lab.config.RagStartupCheck> ragStartupCheck) {
        // 거부될 기동에서는 어떤 소비도 시작하지 않는다(질의 소비자와 같은 규칙). 반응 소비자는 LLM·임베딩을 부르지 않지만 일관되게 둔다.
        ragStartupCheck.getIfAvailable();
        return new RabbitReactionConsumer(broker, mapper, notifier, reaction);
    }
}
