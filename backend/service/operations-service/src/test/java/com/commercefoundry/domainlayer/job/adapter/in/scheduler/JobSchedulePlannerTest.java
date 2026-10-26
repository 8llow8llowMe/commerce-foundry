package com.commercefoundry.domainlayer.job.adapter.in.scheduler;

import static com.commercefoundry.domainlayer.job.support.JobFixtures.properties;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.job.application.port.in.JobSchedulePlannerUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JobSchedulePlannerTest {

    @Test
    @DisplayName("등록 실패는 다음 주기까지 삼키고, 성공하면 유스케이스를 한 번 부른다")
    void delegatesAndKeepsTheScheduleAlive() {
        JobSchedulePlannerUseCase useCase = mock(JobSchedulePlannerUseCase.class);
        JobSchedulePlanner planner = new JobSchedulePlanner(useCase, properties());
        when(useCase.plan()).thenThrow(new IllegalStateException("db")).thenReturn(1);

        assertThatCode(planner::plan).doesNotThrowAnyException();
        assertThatCode(planner::plan).doesNotThrowAnyException();
        verify(useCase, times(2)).plan();
    }
}
