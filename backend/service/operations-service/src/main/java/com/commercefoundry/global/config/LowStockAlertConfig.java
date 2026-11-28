package com.commercefoundry.global.config;

import com.commercefoundry.global.properties.LowStockAlertProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 품절 임박 정기 점검(#229) 전용 1스레드 스케줄러. 게이트가 꺼져 있으면 스레드를 만들지 않는다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LowStockAlertProperties.class)
public class LowStockAlertConfig {
    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty("operations.notifications.low-stock-alert.enabled")
    public ThreadPoolTaskScheduler lowStockAlertTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("low-stock-alert-");
        // 종료 이벤트에서 SSE 전달이 먼저 차단된다. 점검 중 HTTP 의 강제 종료 시한은 보장하지 않는다 — 버려진 점검은 다음 기동에서 처음부터 다시 읽는다.
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(15);
        return scheduler;
    }
}
