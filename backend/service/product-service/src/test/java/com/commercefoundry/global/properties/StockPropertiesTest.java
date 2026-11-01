package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class StockPropertiesTest {

    private static final Map<String, String> VALID = Map.of(
        "stock.reservation.ttl", "30m",
        "stock.reservation.expiry-sweep.initial-delay", "60s",
        "stock.reservation.expiry-sweep.fixed-delay", "60s",
        "stock.reservation.expiry-sweep.batch-size", "100",
        "stock.reservation.expiry-sweep.max-batches-per-run", "10",
        "stock.reservation.expiry-sweep.event-grace", "2m"
    );

    @Test
    @DisplayName("stock.* 가 기간 · 개수로 바인딩된다")
    void binds() {
        StockProperties.Reservation reservation = bind(VALID).reservation();

        assertThat(reservation.ttl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(reservation.expirySweep().initialDelay()).isEqualTo(Duration.ofSeconds(60));
        assertThat(reservation.expirySweep().fixedDelay()).isEqualTo(Duration.ofSeconds(60));
        assertThat(reservation.expirySweep().batchSize()).isEqualTo(100);
        assertThat(reservation.expirySweep().maxBatchesPerRun()).isEqualTo(10);
        assertThat(reservation.expirySweep().eventGrace()).isEqualTo(Duration.ofMinutes(2));
    }

    @ParameterizedTest(name = "event-grace={0}")
    @CsvSource({"-1s", "61m"})
    @DisplayName("event-grace 는 0 이상 1시간 이하다 — 0 은 허용(안전망도 만료 시각에 바로 푼다)")
    void eventGraceRange(String value) {
        Map<String, String> properties = new HashMap<>(VALID);
        properties.put("stock.reservation.expiry-sweep.event-grace", value);
        assertThatThrownBy(() -> bind(properties)).isInstanceOf(BindException.class);

        properties.put("stock.reservation.expiry-sweep.event-grace", "0s");
        assertThat(bind(properties).reservation().expirySweep().eventGrace()).isZero();
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
        "stock.reservation.ttl, 0s",
        "stock.reservation.ttl, -1m",
        "stock.reservation.expiry-sweep.initial-delay, 0s",
        "stock.reservation.expiry-sweep.fixed-delay, 0s",
        "stock.reservation.expiry-sweep.batch-size, 0",
        "stock.reservation.expiry-sweep.max-batches-per-run, 0"
    })
    @DisplayName("0 · 음수면 기동하지 않는다")
    void rejectsNonPositive(String key, String value) {
        Map<String, String> properties = new HashMap<>(VALID);
        properties.put(key, value);

        assertThatThrownBy(() -> bind(properties)).isInstanceOf(BindException.class);
    }

    @Test
    @DisplayName("값이 빠지면 기동하지 않는다")
    void rejectsMissing() {
        Map<String, String> properties = new HashMap<>(VALID);
        properties.remove("stock.reservation.ttl");

        assertThatThrownBy(() -> bind(properties)).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of())).isInstanceOf(BindException.class);
    }

    private static StockProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("stock", StockProperties.class);
    }
}
