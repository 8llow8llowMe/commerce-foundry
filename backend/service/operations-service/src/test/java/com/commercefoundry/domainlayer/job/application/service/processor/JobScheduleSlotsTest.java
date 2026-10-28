package com.commercefoundry.domainlayer.job.application.service.processor;

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

    @Test
    @DisplayName("catch-up 경계는 포함한다 — 슬롯이 정확히 catch-up 전이면 등록한다")
    void includesTheCatchUpBoundary() {
        Optional<JobScheduleSlot> slot = JobScheduleSlots.latest(
            schedule(JobProperties.Planner.Cadence.DAILY, null, null), at(2026, 10, 26, 10, 0), Duration.ofHours(1)
        );

        assertThat(slot).hasValueSatisfying(found -> assertThat(found.scheduledAt()).isEqualTo(at(2026, 10, 26, 9, 0)));
    }

    @Test
    @DisplayName("주: 오늘이 지정 요일이면 시각 전은 지난주 슬롯, 시각 후는 오늘 슬롯이다")
    void weeklyOnTheDayItself() {
        JobProperties.Planner.Schedule monday = schedule(JobProperties.Planner.Cadence.WEEKLY, DayOfWeek.MONDAY, null);

        JobScheduleSlot before = latest(monday, at(2026, 10, 26, 8, 59)).orElseThrow();
        JobScheduleSlot after = latest(monday, at(2026, 10, 26, 9, 0)).orElseThrow();

        assertThat(before.scheduledAt()).isEqualTo(at(2026, 10, 19, 9, 0));
        assertThat(after.scheduledAt()).isEqualTo(at(2026, 10, 26, 9, 0));
        assertThat(after.dedupKey()).isEqualTo("planner:WEEKLY:Asia/Seoul:2026-10-26T09:00:00");
    }

    @Test
    @DisplayName("월: 1월에 그달 시각 전이면 전년 12월 슬롯이다")
    void monthlyCrossesTheYear() {
        JobScheduleSlot slot = latest(schedule(JobProperties.Planner.Cadence.MONTHLY, null, 1), at(2026, 1, 1, 8, 0)).orElseThrow();

        assertThat(slot.scheduledAt()).isEqualTo(at(2025, 12, 1, 9, 0));
        assertThat(slot.dedupKey()).isEqualTo("planner:MONTHLY:Asia/Seoul:2025-12-01T09:00:00");
    }

    @Test
    @DisplayName("DST 로 없는 시각(뉴욕 봄 전환일 02:30)은 한 시간 뒤(03:30 EDT)로 밀려 그날도 슬롯이 하나 생긴다")
    void dailyInADaylightSavingGap() {
        ZoneId newYork = ZoneId.of("America/New_York");
        JobProperties.Planner.Schedule schedule = new JobProperties.Planner.Schedule(
            "REPORT_EXPORT", JobProperties.Planner.Cadence.DAILY, LocalTime.of(2, 30), newYork, null, null, "{}", 0
        );

        JobScheduleSlot slot = latest(schedule, ZonedDateTime.of(2026, 3, 8, 12, 0, 0, 0, newYork).toInstant()).orElseThrow();

        assertThat(slot.scheduledAt()).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
        assertThat(slot.dedupKey()).isEqualTo("planner:DAILY:America/New_York:2026-03-08T03:30:00");
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
