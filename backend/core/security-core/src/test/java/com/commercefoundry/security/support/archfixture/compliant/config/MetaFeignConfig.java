package com.commercefoundry.security.support.archfixture.compliant.config;

import org.springframework.context.annotation.Configuration;

/** 준수 예시 — 합성 애노테이션으로 Feign 을 켜도 메타 체인에 {@code defaultConfiguration} 이 없으면 통과한다. */
@Configuration
@EnableScopedFeignClients
public class MetaFeignConfig {
}
