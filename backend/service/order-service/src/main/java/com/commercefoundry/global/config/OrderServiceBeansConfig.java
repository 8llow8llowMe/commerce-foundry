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
public class OrderServiceBeansConfig {

    /** 주문 요청 기록 lease · 재고 선점 만료 판정의 지금. UTC 로 고정한다 — 서버 시간대에 묶이지 않게. 테스트는 고정 시계를 넣는다. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
