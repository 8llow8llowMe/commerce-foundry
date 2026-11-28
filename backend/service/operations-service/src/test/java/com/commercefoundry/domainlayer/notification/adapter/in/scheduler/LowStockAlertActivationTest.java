package com.commercefoundry.domainlayer.notification.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.notification.application.port.in.LowStockAlertSchedulerUseCase;
import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy;
import com.commercefoundry.global.config.LowStockAlertConfig;
import com.commercefoundry.global.properties.LowStockAlertProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;

/** 품절 임박 정기 점검(#229) 활성화 게이트 — 닫힌 기본값, 켜면 전용 1스레드 스케줄러에 하나만 등록, 설정 범위 검증. */
class LowStockAlertActivationTest {
    private final LowStockAlertSchedulerUseCase useCase = mock(LowStockAlertSchedulerUseCase.class);
    private final TaskScheduler sharedScheduler = mock(TaskScheduler.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(LowStockAlertSchedulerUseCase.class, () -> useCase)
        .withBean("taskScheduler", TaskScheduler.class, () -> sharedScheduler)
        .withUserConfiguration(LowStockAlertConfig.class, LowStockAlertScheduler.class, Scheduling.class);

    @Test
    void absentFlagRegistersNothingAndNeverRuns() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(LowStockAlertScheduler.class).doesNotHaveBean("lowStockAlertTaskScheduler");
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
            assertThat(context.getBean(LowStockAlertProperties.class).enabled()).isFalse();
            verifyNoInteractions(useCase, sharedScheduler);
        });
    }

    @Test
    void explicitFalseRegistersNothing() {
        runner.withPropertyValues("operations.notifications.low-stock-alert.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(LowStockAlertScheduler.class).doesNotHaveBean("lowStockAlertTaskScheduler");
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
            verifyNoInteractions(useCase, sharedScheduler);
        });
    }

    @Test
    void enabledRegistersOneTaskOnDedicatedExecutorWithDefaultDelaysAndDelegates() {
        runner.withPropertyValues("operations.notifications.low-stock-alert.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(LowStockAlertScheduler.class).hasBean("lowStockAlertTaskScheduler");
            var executor = context.getBean("lowStockAlertTaskScheduler", ThreadPoolTaskScheduler.class);
            assertThat(executor.getPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("low-stock-alert-");
            var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
            assertThat(tasks).hasSize(1);
            var task = (FixedDelayTask) tasks.iterator().next().getTask();
            assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(2));
            assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(10));
            assertThat(context.getBean(LowStockAlertProperties.class).settings()).isEqualTo(LowStockAlertPolicy.Settings.defaults());
            verifyNoInteractions(useCase, sharedScheduler);
            task.getRunnable().run();
            verify(useCase).observe();
            verifyNoMoreInteractions(useCase);
            verifyNoInteractions(sharedScheduler);
        });
    }

    @Test
    void configuredValuesBindAndScheduleFollowsThem() {
        runner.withPropertyValues("operations.notifications.low-stock-alert.enabled=true",
            "operations.notifications.low-stock-alert.initial-delay=1d", "operations.notifications.low-stock-alert.fixed-delay=30m",
            "operations.notifications.low-stock-alert.max-pages=40", "operations.notifications.low-stock-alert.resolve-after=12h",
            "operations.notifications.low-stock-alert.reminder-interval=1d", "operations.notifications.low-stock-alert.lock-at-most=3m").run(context -> {
                assertThat(context).hasNotFailed();
                var task = (FixedDelayTask) context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().iterator().next().getTask();
                assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofDays(1));
                assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(30));
                assertThat(context.getBean(LowStockAlertProperties.class).settings())
                    .isEqualTo(new LowStockAlertPolicy.Settings(40, Duration.ofHours(12), Duration.ofDays(1), Duration.ofMinutes(3)));
            });
    }

    @Test
    void outOfRangeMaxPagesFailsStartup() {
        for (var invalid : List.of("0", "41")) {
            runner.withPropertyValues("operations.notifications.low-stock-alert.max-pages=" + invalid)
                .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void durationsArePositiveAndBounded() {
        var defaults = new LowStockAlertProperties(false, null, null, null, null, null, null);
        assertThat(defaults.initialDelay()).isEqualTo(Duration.ofMinutes(2));
        assertThat(defaults.fixedDelay()).isEqualTo(Duration.ofMinutes(10));
        assertThat(defaults.maxPages()).isEqualTo(20);
        assertThat(defaults.settings()).isEqualTo(LowStockAlertPolicy.Settings.defaults());
        for (var invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(400))) {
            assertThatThrownBy(() -> new LowStockAlertProperties(false, invalid, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LowStockAlertProperties(false, null, invalid, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LowStockAlertProperties(false, null, null, null, invalid, null, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LowStockAlertProperties(false, null, null, null, null, invalid, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LowStockAlertProperties(false, null, null, null, null, null, invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {
    }
}
