package com.commercefoundry.domainlayer.order.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.order.application.port.in.OrderSchedulerUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * test 프로파일은 스윕을 끄므로({@code application-test.yml}) 기본값(켜짐)일 때 빈이 만들어지는지는 컨텍스트 로딩 테스트가 보지 못한다 — 여기서 본다.
 */
class OrderExpirySchedulerConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(OrderSchedulerUseCase.class, () -> mock(OrderSchedulerUseCase.class))
        .withUserConfiguration(OrderExpiryScheduler.class);

    @Test
    @DisplayName("enabled 가 없으면(기본 켜짐) 스케줄러 빈을 만든다")
    void createsSchedulerByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(OrderExpiryScheduler.class));
    }

    @Test
    @DisplayName("enabled=true 면 만들고, false 면 만들지 않는다")
    void followsEnabledProperty() {
        runner.withPropertyValues("order.payment-expiry.sweep.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(OrderExpiryScheduler.class));
        runner.withPropertyValues("order.payment-expiry.sweep.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(OrderExpiryScheduler.class));
    }
}
