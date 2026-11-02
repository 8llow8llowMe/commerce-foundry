package com.commercefoundry.domainlayer.order.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.order.application.port.in.OrderAutoConfirmationUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * test 프로파일은 스윕을 끄므로({@code application-test.yml}) 기본값(켜짐)일 때 빈이 만들어지는지는 컨텍스트 로딩 테스트가 보지 못한다 — 여기서 본다.
 */
class OrderAutoConfirmationSchedulerConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(OrderAutoConfirmationUseCase.class, () -> mock(OrderAutoConfirmationUseCase.class))
        .withUserConfiguration(OrderAutoConfirmationScheduler.class);

    @Test
    @DisplayName("enabled 가 없으면(기본 켜짐) 스케줄러 빈을 만든다")
    void createsSchedulerByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(OrderAutoConfirmationScheduler.class));
    }

    @Test
    @DisplayName("enabled=true 면 만들고, false 면 만들지 않는다")
    void followsEnabledProperty() {
        runner.withPropertyValues("order.purchase-confirmation.auto.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(OrderAutoConfirmationScheduler.class));
        runner.withPropertyValues("order.purchase-confirmation.auto.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(OrderAutoConfirmationScheduler.class));
    }
}
