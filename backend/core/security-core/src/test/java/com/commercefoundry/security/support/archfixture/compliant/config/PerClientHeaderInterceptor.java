package com.commercefoundry.security.support.archfixture.compliant.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/** 준수 예시 — 컴포넌트가 아닌 인터셉터 구현. 클라이언트 configuration 에서만 만들어 그 클라이언트에만 붙는다(R5 컴포넌트 규칙에 걸리지 않는다). */
public class PerClientHeaderInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        template.header("X-Client", "per-client");
    }
}
