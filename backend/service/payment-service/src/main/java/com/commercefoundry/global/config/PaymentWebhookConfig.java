package com.commercefoundry.global.config;

import com.commercefoundry.global.properties.PaymentWebhookProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(PaymentWebhookProperties.class)
public class PaymentWebhookConfig {
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler paymentWebhookTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("payment-webhook-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
