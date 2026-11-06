package com.commercefoundry.domainlayer.order.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.order.application.port.in.ClaimCancellationRetrySchedulerUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 결제 후 취소 재시도 스케줄러 (#16 PR-2) 는 기본 꺼짐이다 — 결제 개방(#15)과 함께 {@code order.cancellation.retry.enabled=true} 로 켠다. */
class ClaimCancellationRetrySchedulerConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(ClaimCancellationRetrySchedulerUseCase.class, () -> mock(ClaimCancellationRetrySchedulerUseCase.class))
        .withUserConfiguration(ClaimCancellationRetryScheduler.class);

    @Test
    @DisplayName("enabled 가 없으면(기본 꺼짐) 스케줄러 빈을 만들지 않는다")
    void absentByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ClaimCancellationRetryScheduler.class));
    }

    @Test
    @DisplayName("enabled=true 면 만들고, false 면 만들지 않는다")
    void followsEnabledProperty() {
        runner.withPropertyValues("order.cancellation.retry.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(ClaimCancellationRetryScheduler.class));
        runner.withPropertyValues("order.cancellation.retry.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(ClaimCancellationRetryScheduler.class));
    }
}
