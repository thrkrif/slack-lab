package com.slack.lab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SlackLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(SlackLabApplication.class, args);
    }
}
