package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.job.domain.model.JobHandlerOptions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class JobPropertiesTest {

    /** application.yml 기본값과 같다. */
    private static final Map<String, String> VALID = Map.ofEntries(
        Map.entry("operations.jobs.worker.enabled", "true"),
        Map.entry("operations.jobs.worker.concurrency", "2"),
        Map.entry("operations.jobs.worker.poll-interval", "2s"),
        Map.entry("operations.jobs.worker.initial-delay", "10s"),
        Map.entry("operations.jobs.worker.default-lease", "5m"),
        Map.entry("operations.jobs.worker.heartbeat-interval", "1m"),
        Map.entry("operations.jobs.worker.default-max-run-duration", "30m"),
        Map.entry("operations.jobs.worker.shutdown-grace", "30s"),
        Map.entry("operations.jobs.retry.max-attempts", "3"),
        Map.entry("operations.jobs.retry.base-delay", "30s"),
        Map.entry("operations.jobs.retry.multiplier", "4"),
        Map.entry("operations.jobs.retry.max-delay", "30m"),
        Map.entry("operations.jobs.retry.jitter-ratio", "0.2"),
        Map.entry("operations.jobs.result.max-json-bytes", "65536")
    );

    @Test
    @DisplayName("operations.jobs.* 가 기간 · 개수로 바인딩되고, 핸들러 기본 옵션 · 재시도 정책이 그 값으로 만들어진다")
    void binds() {
        JobProperties properties = bind(VALID);

        assertThat(properties.worker().concurrency()).isEqualTo(2);
        assertThat(properties.worker().heartbeatInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.worker().shutdownGrace()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.result().maxJsonBytes()).isEqualTo(65536);
        assertThat(properties.defaultHandlerOptions())
            .isEqualTo(new JobHandlerOptions(Duration.ofMinutes(5), 3, Duration.ofMinutes(30), 2));
        assertThat(properties.retry().toPolicy().delayAfter(2, 0.5)).isEqualTo(Duration.ofMinutes(2));
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
        "operations.jobs.worker.concurrency, 0",
        "operations.jobs.worker.poll-interval, 0s",
        "operations.jobs.worker.initial-delay, -1s",
        "operations.jobs.worker.default-lease, 0s",
        "operations.jobs.worker.heartbeat-interval, 0s",
        "operations.jobs.worker.default-max-run-duration, 0s",
        "operations.jobs.worker.shutdown-grace, 0s",
        "operations.jobs.retry.max-attempts, 0",
        "operations.jobs.retry.base-delay, 0s",
        "operations.jobs.retry.multiplier, 0.5",
        "operations.jobs.retry.max-delay, 10s",
        "operations.jobs.retry.jitter-ratio, 1",
        "operations.jobs.result.max-json-bytes, 0"
    })
    @DisplayName("0 · 음수 · 범위 밖이면 기동하지 않는다")
    void rejectsInvalid(String key, String value) {
        Map<String, String> properties = new HashMap<>(VALID);
        properties.put(key, value);

        assertThatThrownBy(() -> bind(properties)).isInstanceOf(BindException.class);
    }

    @ParameterizedTest(name = "heartbeat-interval={0}")
    @ValueSource(strings = {"101s", "5m"})
    @DisplayName("heartbeat 주기가 lease(5m)의 1/3 을 넘으면 기동하지 않는다")
    void rejectsHeartbeatLongerThanThirdOfLease(String heartbeatInterval) {
        Map<String, String> properties = new HashMap<>(VALID);
        properties.put("operations.jobs.worker.heartbeat-interval", heartbeatInterval);

        assertThatThrownBy(() -> bind(properties)).isInstanceOf(BindException.class);
    }

    @Test
    @DisplayName("heartbeat 주기가 정확히 lease 의 1/3 이면 뜬다")
    void acceptsHeartbeatAtThirdOfLease() {
        Map<String, String> properties = new HashMap<>(VALID);
        properties.put("operations.jobs.worker.heartbeat-interval", "100s");

        assertThat(bind(properties).worker().heartbeatInterval()).isEqualTo(Duration.ofSeconds(100));
    }

    @Test
    @DisplayName("값이 빠지면 기동하지 않는다")
    void rejectsMissing() {
        Map<String, String> properties = new HashMap<>(VALID);
        properties.remove("operations.jobs.worker.default-lease");

        assertThatThrownBy(() -> bind(properties)).isInstanceOf(BindException.class);
    }

    private static JobProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("operations.jobs", JobProperties.class);
    }
}
