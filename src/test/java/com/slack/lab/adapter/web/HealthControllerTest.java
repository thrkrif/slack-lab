package com.slack.lab.adapter.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slack.lab.core.port.HealthProbe;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 컨트롤러는 어떤 어댑터인지 모르고 HealthProbe의 결과만 503/200으로 옮긴다. */
@WebMvcTest(HealthController.class)
@Import(HealthControllerTest.Probes.class)
class HealthControllerTest {

    static final AtomicBoolean BROKER_UP = new AtomicBoolean(true);

    @TestConfiguration
    static class Probes {
        @Bean
        HealthProbe brokerProbe() {
            return new HealthProbe() {
                @Override
                public String name() {
                    return "rabbitmq";
                }

                @Override
                public boolean up() {
                    return BROKER_UP.get();
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void 의존_시스템이_살아_있으면_200과_UP을_반환한다() throws Exception {
        BROKER_UP.set(true);

        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.rabbitmq").value("UP"));
    }

    @Test
    void 의존_시스템이_죽으면_503과_DOWN을_반환한다() throws Exception {
        BROKER_UP.set(false);

        mvc.perform(get("/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.rabbitmq").value("DOWN"));
    }
}
