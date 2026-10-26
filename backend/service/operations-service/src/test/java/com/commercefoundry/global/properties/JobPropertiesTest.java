package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.job.domain.model.JobHandlerOptions;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
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
        Map.entry("operations.jobs.worker.redelivery-delay", "2s"),
        Map.entry("operations.jobs.worker.default-lease", "5m"),
        Map.entry("operations.jobs.worker.heartbeat-interval", "1m"),
        Map.entry("operations.jobs.worker.default-max-run-duration", "30m"),
        Map.entry("operations.jobs.worker.shutdown-grace", "30s"),
        Map.entry("operations.jobs.lease-sweep.initial-delay", "30s"),
        Map.entry("operations.jobs.lease-sweep.fixed-delay", "30s"),
        Map.entry("operations.jobs.lease-sweep.batch-size", "100"),
        Map.entry("operations.jobs.lease-sweep.max-batches-per-run", "10"),
        Map.entry("operations.jobs.retry.max-attempts", "3"),
        Map.entry("operations.jobs.retry.base-delay", "30s"),
        Map.entry("operations.jobs.retry.multiplier", "4"),
        Map.entry("operations.jobs.retry.max-delay", "30m"),
        Map.entry("operations.jobs.retry.jitter-ratio", "0.2"),
        Map.entry("operations.jobs.result.max-json-bytes", "65536"),
        Map.entry("operations.jobs.planner.enabled", "true"),
        Map.entry("operations.jobs.planner.initial-delay", "1m"),
        Map.entry("operations.jobs.planner.fixed-delay", "1m"),
        Map.entry("operations.jobs.planner.lock-at-most", "5m"),
        Map.entry("operations.jobs.planner.catch-up", "32d")
    );

    @Test
    @DisplayName("operations.jobs.* 가 기간 · 개수로 바인딩되고, 핸들러 기본 옵션 · 재시도 정책이 그 값으로 만들어진다")
    void binds() {
        JobProperties properties = bind(VALID);

        assertThat(properties.worker().concurrency()).isEqualTo(2);
        assertThat(properties.worker().heartbeatInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.worker().shutdownGrace()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.worker().redeliveryDelay()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.leaseSweep()).isEqualTo(new JobProperties.LeaseSweep(Duration.ofSeconds(30), Duration.ofSeconds(30), 100, 10));
        assertThat(properties.result().maxJsonBytes()).isEqualTo(65536);
        assertThat(properties.planner().enabled()).isTrue();
        assertThat(properties.planner().catchUp()).isEqualTo(Duration.ofDays(32));
        assertThat(properties.planner().schedules()).isEmpty();
        assertThat(properties.defaultHandlerOptions())
            .isEqualTo(new JobHandlerOptions(Duration.ofMinutes(5), 3, Duration.ofMinutes(30), 2));
        assertThat(properties.retry().toPolicy().delayAfter(2, 0.5)).isEqualTo(Duration.ofMinutes(2));
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
        "operations.jobs.worker.concurrency, 0",
        "operations.jobs.worker.redelivery-delay, 0s",
        "operations.jobs.worker.redelivery-delay, 61s",
        "operations.jobs.worker.default-lease, 0s",
        "operations.jobs.worker.heartbeat-interval, 0s",
        "operations.jobs.worker.default-max-run-duration, 0s",
        "operations.jobs.worker.shutdown-grace, 0s",
        "operations.jobs.lease-sweep.initial-delay, 0s",
        "operations.jobs.lease-sweep.fixed-delay, -1s",
        "operations.jobs.lease-sweep.batch-size, 0",
        "operations.jobs.lease-sweep.max-batches-per-run, 0",
        "operations.jobs.retry.max-attempts, 0",
        "operations.jobs.retry.base-delay, 0s",
        "operations.jobs.retry.multiplier, 0.5",
        "operations.jobs.retry.max-delay, 10s",
        "operations.jobs.retry.jitter-ratio, 1",
        "operations.jobs.result.max-json-bytes, 0",
        "operations.jobs.planner.initial-delay, 0s",
        "operations.jobs.planner.fixed-delay, 0s",
        "operations.jobs.planner.lock-at-most, 0s",
        "operations.jobs.planner.catch-up, 0s"
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

    @Test
    @DisplayName("일 · 주 · 월 스케줄이 시각 · 시간대 · 요일 · 일자로 바인딩되고, 입력이 없으면 {} 다")
    void bindsSchedules() {
        Map<String, String> properties = new HashMap<>(VALID);
        properties.put("operations.jobs.planner.schedules[0].type", "REPORT_EXPORT");
        properties.put("operations.jobs.planner.schedules[0].cadence", "DAILY");
        properties.put("operations.jobs.planner.schedules[0].time", "09:00");
        properties.put("operations.jobs.planner.schedules[0].zone", "Asia/Seoul");
        properties.put("operations.jobs.planner.schedules[1].type", "REPORT_EXPORT");
        properties.put("operations.jobs.planner.schedules[1].cadence", "WEEKLY");
        properties.put("operations.jobs.planner.schedules[1].time", "09:00");
        properties.put("operations.jobs.planner.schedules[1].zone", "Asia/Seoul");
        properties.put("operations.jobs.planner.schedules[1].day-of-week", "MONDAY");
        properties.put("operations.jobs.planner.schedules[2].type", "REPORT_EXPORT");
        properties.put("operations.jobs.planner.schedules[2].cadence", "MONTHLY");
        properties.put("operations.jobs.planner.schedules[2].time", "09:00");
        properties.put("operations.jobs.planner.schedules[2].zone", "Asia/Seoul");
        properties.put("operations.jobs.planner.schedules[2].day-of-month", "1");

        assertThat(bind(properties).planner().schedules()).containsExactly(
            new JobProperties.Planner.Schedule(
                "REPORT_EXPORT", JobProperties.Planner.Cadence.DAILY, LocalTime.of(9, 0), ZoneId.of("Asia/Seoul"), null, null, "{}", 0
            ),
            new JobProperties.Planner.Schedule(
                "REPORT_EXPORT", JobProperties.Planner.Cadence.WEEKLY, LocalTime.of(9, 0), ZoneId.of("Asia/Seoul"), DayOfWeek.MONDAY, null, "{}", 0
            ),
            new JobProperties.Planner.Schedule(
                "REPORT_EXPORT", JobProperties.Planner.Cadence.MONTHLY, LocalTime.of(9, 0), ZoneId.of("Asia/Seoul"), null, 1, "{}", 0
            )
        );
    }

    @Test
    @DisplayName("스케줄 요일 · 일자 · 종류 · 같은 슬롯이 맞지 않으면 기동하지 않는다")
    void rejectsSchedule() {
        Map<String, String> dailyWithDay = schedule("DAILY");
        dailyWithDay.put("operations.jobs.planner.schedules[0].day-of-week", "MONDAY");
        Map<String, String> weeklyWithoutDay = schedule("WEEKLY");
        Map<String, String> monthlyDay = schedule("MONTHLY");
        monthlyDay.put("operations.jobs.planner.schedules[0].day-of-month", "31");
        Map<String, String> badType = schedule("DAILY");
        badType.put("operations.jobs.planner.schedules[0].type", "report");
        Map<String, String> duplicate = schedule("DAILY");
        duplicate.put("operations.jobs.planner.schedules[1].type", "REPORT_EXPORT");
        duplicate.put("operations.jobs.planner.schedules[1].cadence", "DAILY");
        duplicate.put("operations.jobs.planner.schedules[1].time", "09:00");
        duplicate.put("operations.jobs.planner.schedules[1].zone", "Asia/Seoul");

        assertThatThrownBy(() -> bind(dailyWithDay)).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(weeklyWithoutDay)).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(monthlyDay)).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(badType)).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(duplicate)).isInstanceOf(BindException.class);
    }

    private static Map<String, String> schedule(String cadence) {
        Map<String, String> properties = new HashMap<>(VALID);
        properties.put("operations.jobs.planner.schedules[0].type", "REPORT_EXPORT");
        properties.put("operations.jobs.planner.schedules[0].cadence", cadence);
        properties.put("operations.jobs.planner.schedules[0].time", "09:00");
        properties.put("operations.jobs.planner.schedules[0].zone", "Asia/Seoul");
        return properties;
    }

    private static JobProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("operations.jobs", JobProperties.class);
    }
}
