package com.commercefoundry;

import com.commercefoundry.security.support.ControllerAuthorizationArchTestSupport;

/**
 * 컨트롤러 인가 선언 · 전역 Feign 설정 정적 규칙 — 이 서비스의 main 클래스만 본다. 규칙과 위반 시 고치는 법은 {@link ControllerAuthorizationArchTestSupport}.
 */
class AuthServiceArchitectureTest extends ControllerAuthorizationArchTestSupport {

    @Override
    protected Class<?> applicationClass() {
        return AuthServiceApplication.class;
    }
}
