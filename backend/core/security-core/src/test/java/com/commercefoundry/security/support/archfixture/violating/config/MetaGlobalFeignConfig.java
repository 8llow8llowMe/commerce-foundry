package com.commercefoundry.security.support.archfixture.violating.config;

import org.springframework.context.annotation.Configuration;

/** 위반 예시 — 합성 애노테이션({@link EnableAllFeignClients})으로 {@code defaultConfiguration} 을 받는다. */
@Configuration
@EnableAllFeignClients
public class MetaGlobalFeignConfig {
}
