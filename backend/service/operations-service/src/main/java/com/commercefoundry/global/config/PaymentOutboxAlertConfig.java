package com.commercefoundry.global.config;

import com.commercefoundry.global.properties.PaymentOutboxAlertProperties;
import java.util.function.LongSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(PaymentOutboxAlertProperties.class)
public class PaymentOutboxAlertConfig {
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskExecutor paymentOutboxAlertPushExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("payment-outbox-push-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
    @Bean
    public LongSupplier paymentOutboxAlertTicker() {
        return System::nanoTime;
    }
    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty("operations.notifications.payment-outbox-alert.enabled")
    public ThreadPoolTaskScheduler paymentOutboxAlertTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("payment-outbox-alert-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(15);
        return scheduler;
    }
}
