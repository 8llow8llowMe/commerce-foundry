package com.commercefoundry.domainlayer.job.application.service;

import com.commercefoundry.domainlayer.job.application.model.JobScheduleSlot;
import com.commercefoundry.global.properties.JobProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 일 · 주 · 월 스케줄의 가장 최근 슬롯. 놓친 슬롯을 전부 채우지 않는다 — {@code catch-up} 안의 마지막 시각 하나만 고른다.
 */
public final class JobScheduleSlots {

    private static final DateTimeFormatter LOCAL_SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private JobScheduleSlots() {
    }

    /**
     * @return 지금 이전이면서 {@code catchUp} 안인 가장 최근 슬롯. 그 시각이 아직 안 됐거나 너무 오래됐으면 비어 있다
     */
    public static Optional<JobScheduleSlot> latest(JobProperties.Planner.Schedule schedule, Instant now, Duration catchUp) {
        ZonedDateTime slot = switch (schedule.cadence()) {
            case DAILY -> daily(schedule, now);
            case WEEKLY -> weekly(schedule, now);
            case MONTHLY -> monthly(schedule, now);
        };
        Instant scheduledAt = slot.toInstant();
        if (scheduledAt.isAfter(now) || scheduledAt.isBefore(now.minus(catchUp))) {
            return Optional.empty();
        }
        String dedupKey = "planner:" + schedule.cadence() + ":" + schedule.zone() + ":" + LOCAL_SLOT.format(slot);
        return Optional.of(new JobScheduleSlot(scheduledAt, dedupKey));
    }

    private static ZonedDateTime daily(JobProperties.Planner.Schedule schedule, Instant now) {
        LocalDate date = now.atZone(schedule.zone()).toLocalDate();
        ZonedDateTime candidate = at(schedule, date);
        if (candidate.toInstant().isAfter(now)) {
            candidate = at(schedule, date.minusDays(1));
        }
        return candidate;
    }

    private static ZonedDateTime weekly(JobProperties.Planner.Schedule schedule, Instant now) {
        LocalDate date = now.atZone(schedule.zone()).toLocalDate();
        int daysAfterSlot = date.getDayOfWeek().getValue() - schedule.dayOfWeek().getValue();
        if (daysAfterSlot < 0) {
            daysAfterSlot += 7;
        }
        ZonedDateTime candidate = at(schedule, date.minusDays(daysAfterSlot));
        if (candidate.toInstant().isAfter(now)) {
            candidate = at(schedule, date.minusDays(daysAfterSlot + 7L));
        }
        return candidate;
    }

    private static ZonedDateTime monthly(JobProperties.Planner.Schedule schedule, Instant now) {
        YearMonth month = YearMonth.from(now.atZone(schedule.zone()));
        ZonedDateTime candidate = at(schedule, month.atDay(schedule.dayOfMonth()));
        if (candidate.toInstant().isAfter(now)) {
            candidate = at(schedule, month.minusMonths(1).atDay(schedule.dayOfMonth()));
        }
        return candidate;
    }

    private static ZonedDateTime at(JobProperties.Planner.Schedule schedule, LocalDate date) {
        return ZonedDateTime.of(date, schedule.time(), schedule.zone());
    }
}
