package com.commercefoundry.ai.config;

import com.commercefoundry.ai.client.AiClient;
import com.commercefoundry.ai.client.AiClients;
import com.commercefoundry.ai.properties.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * AI 를 쓰는 서비스의 {@code *BeansConfig} 가 {@code @Import} 한다 ({@code bootstrap-conventions.md} AI). 프로퍼티는
 * {@link AiPropertiesConfig} 가 등록한다. 컴포넌트 스캔에 걸리지 않게 {@code @Configuration} 을 달지 않는다.
 *
 * <p>자동 구성이 아니다 — 쓰는 서비스만 고른다(지금은 operations-service 하나 예정). {@code ai.enabled=false} 여도 {@link AiClient} 빈은
 * 언제나 하나 있고 호출은 {@code AiException(DISABLED)} 다. 서비스 어댑터는 빈 유무를 분기하지 않는다 ({@code StorageConfigurer} 와 같은 판단).
 */
@Slf4j
public class AiConfigurer {

    /** static — {@code BeanFactoryPostProcessor} 는 설정 클래스 인스턴스보다 먼저 만들어져야 한다. */
    @Bean
    public static AiStartupGuard aiStartupGuard(Environment environment) {
        return new AiStartupGuard(environment);
    }

    /** 켜져 있으면 SDK 클라이언트를 품는다 — 스프링이 종료 때 {@code close()} 를 추론해 부른다. */
    @Bean
    public AiClient aiClient(AiProperties aiProperties) {
        if (!aiProperties.enabled()) {
            log.info("ai client disabled. ai.enabled=false");
        }
        return AiClients.create(aiProperties);
    }
}
