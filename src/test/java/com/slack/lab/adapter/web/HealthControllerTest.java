package com.slack.lab.adapter.web;

import com.slack.lab.adapter.redis.RedisHealthProbe;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
@Import(RedisHealthProbe.class)
class HealthControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RedisConnectionFactory redis;

    @Test
    void Redis가_응답하면_200과_UP을_반환한다() throws Exception {
        RedisConnection connection = Mockito.mock(RedisConnection.class);
        given(connection.ping()).willReturn("PONG");
        given(redis.getConnection()).willReturn(connection);

        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.redis").value("UP"));
    }

    @Test
    void Redis에_연결할_수_없으면_503과_DOWN을_반환한다() throws Exception {
        given(redis.getConnection()).willThrow(new RedisConnectionFailureException("down"));

        mvc.perform(get("/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.redis").value("DOWN"));
    }
}
