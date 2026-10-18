package com.commercefoundry.global.config;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
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
    QuerydslConfigurer.class,
    ResourceServerSecurityConfigurer.class,
    SnowflakeConfigurer.class,
    SwaggerSecurityConfigurer.class
})
public class ProductServiceBeansConfig {

    /** 업무 시각(재고 선점 만료)의 기준. 테스트가 고정 시계로 바꿔 만료를 제어한다. 감사 컬럼(created_at · updated_at)은 이 시계를 쓰지 않는다. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
