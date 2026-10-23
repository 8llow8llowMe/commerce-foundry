package com.commercefoundry.global.config;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.config.SnowflakeConfigurer;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

@Configuration
@Import({
    InternalResourceServerConfigurer.class,
    QuerydslConfigurer.class,
    ResourceServerSecurityConfigurer.class,
    SnowflakeConfigurer.class,
    SwaggerSecurityConfigurer.class
})
public class PaymentServiceBeansConfig {

    /**
     * 토스 키 · 타임아웃 기동 가드 ({@code payment-guide.md} §2). static — {@code BeanFactoryPostProcessor} 는 설정 클래스 인스턴스보다 먼저 만들어져야 한다.
     * Feign 클라이언트 · 프로퍼티 빈이 생기기 전에 설정을 검사한다.
     */
    @Bean
    public static TossPaymentsStartupGuard tossPaymentsStartupGuard(Environment environment) {
        return new TossPaymentsStartupGuard(environment);
    }
}
