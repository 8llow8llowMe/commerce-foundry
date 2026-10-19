package com.commercefoundry;

import com.commercefoundry.common.support.HexagonalArchitectureRules;
import com.commercefoundry.security.support.ControllerAuthorizationArchTestSupport;

/**
 * 컨트롤러 인가 선언 · 전역 Feign 설정 · 헥사고날 계층 의존 정적 규칙 — 이 서비스의 main 클래스만 본다.
 * 규칙과 위반 시 고치는 법은 {@link ControllerAuthorizationArchTestSupport}(인가 R0~R7) · {@link HexagonalArchitectureRules}(계층 H1~H9).
 * {@code applicationClass()} 는 인터페이스 메서드도 구현하므로 public 이다.
 */
class AuthServiceArchitectureTest extends ControllerAuthorizationArchTestSupport implements HexagonalArchitectureRules {

    @Override
    public Class<?> applicationClass() {
        return AuthServiceApplication.class;
    }
}
