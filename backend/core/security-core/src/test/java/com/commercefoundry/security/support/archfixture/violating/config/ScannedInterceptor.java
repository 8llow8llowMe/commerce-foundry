package com.commercefoundry.security.support.archfixture.violating.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.stereotype.Component;

/** 위반 예시 — 컴포넌트 스캔되는 인터셉터. {@code @Bean} 없이도 모든 Feign 클라이언트에 붙는다. */
@Component
public class ScannedInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        template.header("X-Relay", "user");
    }
}
