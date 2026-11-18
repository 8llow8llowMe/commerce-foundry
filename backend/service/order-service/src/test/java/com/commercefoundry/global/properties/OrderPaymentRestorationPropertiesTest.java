package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OrderPaymentRestorationPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrderPaymentRestorationProperties.class)
    static class Config { }

    @Test
    void defaultIsFiveMinutes() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OrderPaymentRestorationProperties.class).minAge()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "1d"})
    void inclusiveBoundsAreAllowed(String value) {
        runner.withPropertyValues("order.payment-restoration.min-age=" + value).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1ns", "86400000000001ns"})
    void outsideBoundsFailsStartup(String value) {
        runner.withPropertyValues("order.payment-restoration.min-age=" + value).run(context -> assertThat(context).hasFailed());
    }
}
