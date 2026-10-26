package com.commercefoundry.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 알림 SSE 주석 전용 스레드. {@code defaultCandidate = false} 라 Boot 의 {@code taskScheduler} 를 밀어내지 않는다 (#213).
 */
@Configuration
public class NotificationStreamSchedulerConfig {

    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler notificationStreamTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-stream-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
