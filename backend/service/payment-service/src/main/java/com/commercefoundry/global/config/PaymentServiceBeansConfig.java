package com.commercefoundry.global.config;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.global.properties.PaymentGateProperties;
import com.commercefoundry.global.properties.PaymentRecoveryProperties;
import com.commercefoundry.messaging.config.MessagingConfigurer;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.config.SnowflakeConfigurer;
import com.commercefoundry.security.internal.InternalTokenIssuerConfigurer;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * {@link InternalTokenIssuerConfigurer} — internal Feign 클라이언트(order-service 주문 요약)가 싣는 서비스 토큰 발급기다. 빠뜨리면 클라이언트를 만들 때 기동이 멈춘다
 * ({@code security-guide.md} §9-5). {@link MessagingConfigurer} — 결제 승인 이벤트 발행기 · outbox 릴레이다 (#308, {@code payment-guide.md} §6).
 */
@Configuration
@Import({
    InternalResourceServerConfigurer.class,
    InternalTokenIssuerConfigurer.class,
    MessagingConfigurer.class,
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

    /** 결제 게이트가 열려 있으면 outbox 테이블이 있어야 기동한다 (#308, {@code payment-guide.md} §6). */
    @Bean
    public PaymentOutboxSchemaGuard paymentOutboxSchemaGuard(
        DataSource dataSource, PaymentGateProperties paymentGateProperties, PaymentRecoveryProperties recovery
    ) {
        return new PaymentOutboxSchemaGuard(dataSource, paymentGateProperties, recovery.enabled());
    }

    /** 결제 기한 판정 · 승인 시작 시각의 기준 시계 (UTC). */
    @Bean
    public Clock paymentClock() {
        return Clock.systemUTC();
    }
}
