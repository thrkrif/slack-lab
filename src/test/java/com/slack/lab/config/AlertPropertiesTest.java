package com.slack.lab.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AlertPropertiesTest {

    @Test
    void 시크릿과_채널이_모두_있어야_켜진다() {
        assertThat(new AlertProperties("s", "C").enabled()).isTrue();
        assertThat(new AlertProperties("", "C").enabled()).isFalse();
        assertThat(new AlertProperties("s", " ").enabled()).isFalse();
    }
}
