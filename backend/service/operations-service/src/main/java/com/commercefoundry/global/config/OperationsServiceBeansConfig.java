package com.commercefoundry.global.config;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.messaging.config.MessagingConfigurer;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.config.SnowflakeConfigurer;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({
    InternalResourceServerConfigurer.class,
    MessagingConfigurer.class,
    QuerydslConfigurer.class,
    ResourceServerSecurityConfigurer.class,
    SnowflakeConfigurer.class,
    SwaggerSecurityConfigurer.class
})
public class OperationsServiceBeansConfig {

    /** 업무 시각(작업 예약 · lease 만료 · 재시도 시각)의 기준. 테스트가 고정 시계로 바꿔 시각을 제어한다. 감사 컬럼(created_at · updated_at)은 이 시계를 쓰지 않는다. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
