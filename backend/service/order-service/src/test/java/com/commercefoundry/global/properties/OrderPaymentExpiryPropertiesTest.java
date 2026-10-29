package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OrderPaymentExpiryPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrderPaymentExpiryProperties.class)
    static class Config {
    }

    @Test
    @DisplayName("값이 없으면 기본값(켜짐 · 30초 · 100건 · 10배치)으로 뜬다")
    void defaultsAreBounded() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(OrderPaymentExpiryProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.interval()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.batchSize()).isEqualTo(100);
            assertThat(properties.maxBatchesPerRun()).isEqualTo(10);
        });
    }

    @ParameterizedTest
    @DisplayName("간격이 1ms 미만이거나 건수가 범위를 벗어나면 기동하지 않고, 메시지에 키와 받은 값을 싣는다")
    @CsvSource({
        "interval, 0s, PT0S", "interval, -1s, PT-1S", "interval, 1ns, PT0.000000001S",
        "batch-size, 0, 0", "batch-size, -1, -1", "batch-size, 101, 101",
        "max-batches-per-run, 0, 0", "max-batches-per-run, -1, -1", "max-batches-per-run, 11, 11"
    })
    void invalidConfigurationFailsStartup(String key, String value, String received) {
        runner.withPropertyValues("order.payment-expiry.sweep." + key + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            Throwable cause = context.getStartupFailure();
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertThat(cause).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("order.payment-expiry.sweep." + key + " ")
                .hasMessageEndingWith(": " + received);
        });
    }
}
