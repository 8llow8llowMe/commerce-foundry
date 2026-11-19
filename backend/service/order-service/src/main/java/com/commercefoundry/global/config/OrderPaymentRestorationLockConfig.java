package com.commercefoundry.global.config;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 복원 스윕의 실행 선행 조건. JdbcLockProvider를 공용 빈으로 등록하지 않는다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "order.payment-restoration.sweep", name = "enabled", havingValue = "true")
public class OrderPaymentRestorationLockConfig {

    @Bean
    OrderPaymentRestorationLock orderPaymentRestorationLock(DataSource dataSource, Clock clock) {
        return new OrderPaymentRestorationLock(dataSource, clock);
    }

    @Bean
    OrderPaymentRestorationSchemaGuard orderPaymentRestorationSchemaGuard(DataSource dataSource) {
        return new OrderPaymentRestorationSchemaGuard(dataSource);
    }
}
