package com.commercefoundry.domainlayer.job.application.service;

import static com.commercefoundry.domainlayer.job.support.JobFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.job.application.service.processor.JobSchedulePlannerProcessor;
import com.commercefoundry.global.properties.JobProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JobSchedulePlannerFacadeTest {

    private static final Instant NOW = Instant.parse("2026-10-26T01:00:00Z");

    @Test
    @DisplayName("스케줄이 없으면 등록을 부르지 않는다")
    void skipsWhenThereIsNothingToPlan() {
        JobSchedulePlannerProcessor processor = mock(JobSchedulePlannerProcessor.class);

        assertThat(facade(processor, properties()).plan()).isZero();
        verifyNoInteractions(processor);
    }

    @Test
    @DisplayName("스케줄이 있으면 지금 시각으로 등록한다")
    void plansAtTheClock() {
        JobSchedulePlannerProcessor processor = mock(JobSchedulePlannerProcessor.class);
        when(processor.plan(NOW)).thenReturn(1);

        assertThat(facade(processor, planner(daily())).plan()).isEqualTo(1);
        verify(processor).plan(NOW);
    }

    private static JobSchedulePlannerFacade facade(JobSchedulePlannerProcessor processor, JobProperties jobProperties) {
        return new JobSchedulePlannerFacade(processor, jobProperties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static JobProperties planner(JobProperties.Planner.Schedule... schedules) {
        JobProperties defaults = properties();
        return new JobProperties(
            defaults.worker(), defaults.leaseSweep(), defaults.retry(), defaults.result(),
            new JobProperties.Planner(true, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofDays(32), List.of(schedules))
        );
    }

    private static JobProperties.Planner.Schedule daily() {
        return new JobProperties.Planner.Schedule(
            "REPORT_EXPORT", JobProperties.Planner.Cadence.DAILY, LocalTime.of(9, 0), ZoneId.of("Asia/Seoul"), null, null, "{}", 0
        );
    }
}
