package com.commercefoundry.domainlayer.job.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.job.application.model.JobScheduleSlot;
import com.commercefoundry.global.properties.JobProperties;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JobScheduleSlotsTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("시각이 지난 오늘은 오늘 슬롯이고, 시각 전이면 어제 슬롯이다")
    void dailyUsesTheLatestClockTime() {
        JobProperties.Planner.Schedule schedule = schedule(JobProperties.Planner.Cadence.DAILY, null, null);

        JobScheduleSlot after = latest(schedule, at(2026, 10, 26, 10, 0)).orElseThrow();
        JobScheduleSlot before = latest(schedule, at(2026, 10, 26, 8, 0)).orElseThrow();

        assertThat(after.scheduledAt()).isEqualTo(at(2026, 10, 26, 9, 0));
        assertThat(after.dedupKey()).isEqualTo("planner:DAILY:Asia/Seoul:2026-10-26T09:00:00");
        assertThat(before.scheduledAt()).isEqualTo(at(2026, 10, 25, 9, 0));
        assertThat(before.dedupKey()).isEqualTo("planner:DAILY:Asia/Seoul:2026-10-25T09:00:00");
    }

    @Test
    @DisplayName("주는 지정 요일의 가장 최근 시각이고, 월은 1~28일 중 가장 최근 시각이다")
    void weeklyAndMonthly() {
        JobScheduleSlot weekly = latest(
            schedule(JobProperties.Planner.Cadence.WEEKLY, DayOfWeek.MONDAY, null), at(2026, 10, 25, 10, 0)
        ).orElseThrow();
        JobScheduleSlot monthly = latest(
            schedule(JobProperties.Planner.Cadence.MONTHLY, null, 1), at(2026, 10, 1, 8, 0)
        ).orElseThrow();

        assertThat(weekly.scheduledAt()).isEqualTo(at(2026, 10, 19, 9, 0));
        assertThat(weekly.dedupKey()).isEqualTo("planner:WEEKLY:Asia/Seoul:2026-10-19T09:00:00");
        assertThat(monthly.scheduledAt()).isEqualTo(at(2026, 9, 1, 9, 0));
        assertThat(monthly.dedupKey()).isEqualTo("planner:MONTHLY:Asia/Seoul:2026-09-01T09:00:00");
    }

    @Test
    @DisplayName("catch-up 보다 오래된 슬롯은 채우지 않는다")
    void skipsASlotOlderThanCatchUp() {
        Optional<JobScheduleSlot> slot = JobScheduleSlots.latest(
            schedule(JobProperties.Planner.Cadence.DAILY, null, null), at(2026, 10, 26, 10, 0), Duration.ofMinutes(30)
        );

        assertThat(slot).isEmpty();
    }

    private static Optional<JobScheduleSlot> latest(JobProperties.Planner.Schedule schedule, Instant now) {
        return JobScheduleSlots.latest(schedule, now, Duration.ofDays(32));
    }

    private static JobProperties.Planner.Schedule schedule(JobProperties.Planner.Cadence cadence, DayOfWeek dayOfWeek, Integer dayOfMonth) {
        return new JobProperties.Planner.Schedule("REPORT_EXPORT", cadence, LocalTime.of(9, 0), SEOUL, dayOfWeek, dayOfMonth, "{}", 0);
    }

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, SEOUL).toInstant();
    }
}
