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

class OrderAutoConfirmationPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrderAutoConfirmationProperties.class)
    static class Config {
    }

    @Test
    @DisplayName("값이 없으면 기본값(켜짐 · 7일 · 1시간 · 100건 · 10배치)으로 뜬다")
    void defaultsAreBounded() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(OrderAutoConfirmationProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.period()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.interval()).isEqualTo(Duration.ofHours(1));
            assertThat(properties.batchSize()).isEqualTo(100);
            assertThat(properties.maxBatchesPerRun()).isEqualTo(10);
        });
    }

    @Test
    void acceptsBothPeriodBoundaries() {
        for (String period : new String[]{"1d", "30d"}) {
            runner.withPropertyValues("order.purchase-confirmation.auto.period=" + period)
                .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @ParameterizedTest
    @DisplayName("기간 · 간격 · 배치가 범위를 벗어나면 기동하지 않는다")
    @CsvSource({
        "period, 23h, PT23H", "period, 31d, PT744H", "period, -1d, PT-24H",
        "interval, 0s, PT0S", "interval, -1s, PT-1S", "interval, 1ns, PT0.000000001S",
        "batch-size, 0, 0", "batch-size, -1, -1", "batch-size, 101, 101",
        "max-batches-per-run, 0, 0", "max-batches-per-run, -1, -1", "max-batches-per-run, 11, 11"
    })
    void invalidConfigurationFailsStartup(String key, String value, String received) {
        runner.withPropertyValues("order.purchase-confirmation.auto." + key + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            Throwable cause = context.getStartupFailure();
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertThat(cause).isInstanceOf(IllegalArgumentException.class);
        });
    }
}
