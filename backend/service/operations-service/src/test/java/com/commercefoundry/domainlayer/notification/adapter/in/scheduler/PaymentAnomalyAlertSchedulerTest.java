package com.commercefoundry.domainlayer.notification.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.notification.application.port.in.PaymentAnomalyAlertSchedulerUseCase;
import com.commercefoundry.global.config.PaymentAnomalyAlertConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;

class PaymentAnomalyAlertSchedulerTest {
    private final PaymentAnomalyAlertSchedulerUseCase useCase = mock(PaymentAnomalyAlertSchedulerUseCase.class);
    private final TaskScheduler sharedScheduler = mock(TaskScheduler.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(PaymentAnomalyAlertSchedulerUseCase.class, () -> useCase)
        .withBean("taskScheduler", TaskScheduler.class, () -> sharedScheduler)
        .withUserConfiguration(PaymentAnomalyAlertConfig.class, PaymentAnomalyAlertScheduler.class, Scheduling.class);

    @Test
    void absentFlagDisablesSchedulerAndDedicatedExecutor() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PaymentAnomalyAlertScheduler.class)
                .doesNotHaveBean("paymentAnomalyAlertTaskScheduler");
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
            verifyNoInteractions(useCase, sharedScheduler);
        });
    }

    @Test
    void explicitFalseDisablesSchedulerAndDedicatedExecutor() {
        runner.withPropertyValues("operations.notifications.payment-anomaly-alert.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PaymentAnomalyAlertScheduler.class)
                .doesNotHaveBean("paymentAnomalyAlertTaskScheduler");
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
            verifyNoInteractions(useCase, sharedScheduler);
        });
    }

    @Test
    void enabledRegistersOneMinuteFixedDelayOnDedicatedExecutorAndDelegatesToUseCase() {
        runner.withPropertyValues("operations.notifications.payment-anomaly-alert.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PaymentAnomalyAlertScheduler.class)
                .hasBean("paymentAnomalyAlertTaskScheduler");
            var executor = context.getBean("paymentAnomalyAlertTaskScheduler", ThreadPoolTaskScheduler.class);
            assertThat(executor.getPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("payment-anomaly-alert-");
            var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
            assertThat(tasks).hasSize(1);
            var task = (FixedDelayTask) tasks.iterator().next().getTask();
            assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(1));
            assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(1));
            verifyNoInteractions(useCase, sharedScheduler);
            task.getRunnable().run();
            verify(useCase).observe();
            verifyNoMoreInteractions(useCase);
            verifyNoInteractions(sharedScheduler);
        });
    }

    @Test
    void scheduledDelaysFollowDurationProperties() {
        runner.withPropertyValues("operations.notifications.payment-anomaly-alert.enabled=true",
            "operations.notifications.payment-anomaly-alert.initial-delay=1d",
            "operations.notifications.payment-anomaly-alert.fixed-delay=2m").run(context -> {
                assertThat(context).hasNotFailed();
                var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
                assertThat(tasks).hasSize(1);
                var task = (FixedDelayTask) tasks.iterator().next().getTask();
                assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofDays(1));
                assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(2));
                verifyNoInteractions(useCase, sharedScheduler);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {
    }
}
