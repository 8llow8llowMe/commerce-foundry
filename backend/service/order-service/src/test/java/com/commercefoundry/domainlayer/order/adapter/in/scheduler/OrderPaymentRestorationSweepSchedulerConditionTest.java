package com.commercefoundry.domainlayer.order.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.order.application.port.in.PaymentRestorationSweepUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 결제 반영 재고 복원 스윕 (#582) 은 기본 꺼짐이다 — DDL 뒤 {@code order.payment-restoration.sweep.enabled=true} 로 켠다. */
class OrderPaymentRestorationSweepSchedulerConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(PaymentRestorationSweepUseCase.class, () -> mock(PaymentRestorationSweepUseCase.class))
        .withUserConfiguration(OrderPaymentRestorationSweepScheduler.class);

    @Test
    @DisplayName("enabled 가 없으면(기본 꺼짐) 스케줄러 빈을 만들지 않는다")
    void absentByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(OrderPaymentRestorationSweepScheduler.class));
    }

    @Test
    @DisplayName("enabled=true 면 만들고, false 면 만들지 않는다")
    void followsEnabledProperty() {
        runner.withPropertyValues("order.payment-restoration.sweep.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(OrderPaymentRestorationSweepScheduler.class));
        runner.withPropertyValues("order.payment-restoration.sweep.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(OrderPaymentRestorationSweepScheduler.class));
    }
}
