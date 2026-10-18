package com.commercefoundry.security.support.archfixture.violating.config;

import com.commercefoundry.security.internal.InternalTokenIssuer;
import com.commercefoundry.security.internal.client.InternalServiceTokenCapability;
import com.commercefoundry.security.support.archfixture.FakeEnableFeignClients;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 위반 예시 — 모든 Feign 클라이언트에 붙는 설정. Capability 구현 타입을 반환해도 잡는다. */
@Configuration
@FakeEnableFeignClients(defaultConfiguration = GlobalFeignConfig.class)
public class GlobalFeignConfig {

    @Bean
    RequestInterceptor userTokenRelay() {
        return template -> template.header("X-Relay", "user");
    }

    @Bean
    InternalServiceTokenCapability serviceTokenEverywhere(InternalTokenIssuer issuer) {
        return new InternalServiceTokenCapability(issuer);
    }
}
