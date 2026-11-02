package com.commercefoundry.global.config;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.config.SnowflakeConfigurer;
import com.commercefoundry.security.internal.InternalTokenIssuerConfigurer;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({
    InternalResourceServerConfigurer.class,
    InternalTokenIssuerConfigurer.class,
    QuerydslConfigurer.class,
    ResourceServerSecurityConfigurer.class,
    SnowflakeConfigurer.class,
    SwaggerSecurityConfigurer.class
})
public class DeliveryServiceBeansConfig {

    /** 송장 등록 시각(= order 에 보내는 출고 시각, #18). UTC 다 — 테스트는 고정 시계로 바꾼다. */
    @Bean
    public Clock deliveryClock() {
        return Clock.systemUTC();
    }
}
