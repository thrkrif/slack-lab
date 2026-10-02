package com.slack.lab.adapter.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.QueueProperties;
import com.slack.lab.config.RabbitProperties;
import com.slack.lab.config.WorkerProperties;
import com.slack.lab.core.service.EventProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** RabbitMQ 백엔드는 M22 배선이 끝나기 전까지 실수로 켜지지 않는다(반쯤 배선된 앱이 조용히 재시도를 잃는 것을 막는다). */
class RabbitConfigTest {

    ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(RabbitConfig.class)
                .withBean(RabbitProperties.class, () -> new RabbitProperties("localhost", 5672, "guest", "guest", "/",
                        "q", 1000, 3))
                .withBean(WorkerProperties.class, () -> new WorkerProperties(1))
                .withBean(QueueProperties.class, () -> new QueueProperties("s", "g", 150, 100_000))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(EventProcessor.class, () -> mock(EventProcessor.class));
    }

    @Test
    void 백엔드를_지정하지_않으면_RabbitMQ_빈이_하나도_생기지_않는다() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeanNamesForType(RabbitBroker.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(RabbitConsumer.class)).isEmpty();
        });
    }

    @Test
    void 프리뷰_표시_없이_RabbitMQ_백엔드를_켜면_기동을_거부한다() {
        runner().withPropertyValues("queue.backend=rabbitmq").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("queue.rabbitmq-preview");
        });
    }
}
