package com.commercefoundry.global.config;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.config.SnowflakeConfigurer;
import com.commercefoundry.redis.config.RedisConfigurer;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({
    InternalResourceServerConfigurer.class,
    QuerydslConfigurer.class,
    RedisConfigurer.class,
    ResourceServerSecurityConfigurer.class,
    SnowflakeConfigurer.class,
    SwaggerSecurityConfigurer.class
})
public class SupportServiceBeansConfig {

    /** 업무 시각(대화 상태 전이 · 비회원 세션 만료 · WS 티켓 만료)의 기준. UTC 다 — 테스트는 고정 시계로 바꾼다. 감사 컬럼은 이 시계를 쓰지 않는다. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
