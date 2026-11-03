package com.commercefoundry.domainlayer.notification.domain.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.commercefoundry.domainlayer.notification.adapter.in.scheduler.PaymentOutboxAlertScheduler;
import com.commercefoundry.domainlayer.notification.application.port.in.PaymentOutboxAlertSchedulerUseCase;
import com.commercefoundry.global.config.PaymentOutboxAlertConfig;
import com.commercefoundry.global.properties.PaymentOutboxAlertProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PaymentOutboxAlertActivationTest {
    @Test
    void defaultOffAndExplicitOnIndependentOfMessaging() {
        var runner = new ApplicationContextRunner().withUserConfiguration(PaymentOutboxAlertConfig.class, PaymentOutboxAlertScheduler.class)
            .withBean(PaymentOutboxAlertSchedulerUseCase.class, () -> mock(PaymentOutboxAlertSchedulerUseCase.class));
        runner.run(c -> assertThat(c).doesNotHaveBean(PaymentOutboxAlertScheduler.class).doesNotHaveBean("paymentOutboxAlertTaskScheduler"));
        runner.withPropertyValues("operations.notifications.payment-outbox-alert.enabled=true", "commerce.messaging.enabled=false")
            .run(c -> {
                assertThat(c).hasSingleBean(PaymentOutboxAlertScheduler.class).hasBean("paymentOutboxAlertTaskScheduler");
                assertThat(c.getBean(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            });
    }
    @Test
    void durationsArePositiveAndBounded() {
        var defaults = new PaymentOutboxAlertProperties(false, null, null, null, null, null, null);
        assertThat(defaults.threshold()).isEqualTo(Duration.ofMinutes(5));
        for (var invalid : java.util.List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(8))) {
            assertThatThrownBy(() -> new PaymentOutboxAlertProperties(false, invalid, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
