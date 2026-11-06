package com.commercefoundry.global.config;

import com.commercefoundry.global.properties.PaymentAnomalyAlertProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentAnomalyAlertProperties.class)
public class PaymentAnomalyAlertConfig {
    @Bean(defaultCandidate = false)
    @ConditionalOnBooleanProperty("operations.notifications.payment-anomaly-alert.enabled")
    public ThreadPoolTaskScheduler paymentAnomalyAlertTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("payment-anomaly-alert-");
        // 종료 이벤트에서 push가 먼저 차단된다. 수집 중 HTTP의 강제 종료 시한은 보장하지 않는다.
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(15);
        return scheduler;
    }
}
