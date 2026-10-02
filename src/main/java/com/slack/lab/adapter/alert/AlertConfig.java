package com.slack.lab.adapter.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.AlertProperties;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AlertConfig {

    @Bean
    @ConditionalOnRole({AppRole.RECEIVER, AppRole.ALL})
    CloudWatchAlertNormalizer cloudWatchAlertNormalizer(ObjectMapper mapper, AlertProperties props) {
        return new CloudWatchAlertNormalizer(mapper, props.channel());
    }
}
