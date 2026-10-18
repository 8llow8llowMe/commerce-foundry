package com.commercefoundry.security.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 런타임 가드(인가 스모크)와 정적 규칙(ArchUnit)이 같이 쓰는 {@code @PreAuthorize} 식 판정. 통과시키는 쪽이 틀리면 공개 API 가 되므로 거절 케이스를 넓게 둔다. */
class AuthorizationExpressionsTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "hasRole('ADMIN')",
        "  hasRole( 'ADMIN' )  ",
        "hasRole('ADMIN') and isFullyAuthenticated()",
        "hasRole('ADMIN') AND isFullyAuthenticated()",
        "hasRole('ADMIN') && isFullyAuthenticated()",
        "hasRole('ADMIN') and !hasAuthority('SCOPE_readonly')",
        "hasRole('ADMIN') and (hasAuthority('SCOPE_a') or hasAuthority('SCOPE_b'))",
        "hasRole('ADMIN') and hasAuthority('SCOPE_or')",
        "hasRole('ADMIN') and principal.name != 'operator'"
    })
    @DisplayName("ADMIN 이 필요조건인 식은 통과한다 — 단독 · and 로 좁힘 · 괄호 · 문자열 안의 or")
    void adminRoleRequired(String expression) {
        assertThat(AuthorizationExpressions.requiresAdminRole(expression)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
        "",
        "permitAll()",
        "isAuthenticated()",
        "hasRole('CUSTOMER')",
        "hasAnyRole('ADMIN', 'CUSTOMER')",
        "hasAuthority('ROLE_ADMIN')",
        "hasRole('ADMIN') or isAuthenticated()",
        "hasRole('ADMIN') OR isAuthenticated()",
        "hasRole('ADMIN') || isAuthenticated()",
        "hasRole('ADMIN') and isAuthenticated() or permitAll()",
        "hasRole('ADMIN') and isAuthenticated() ? true : true",
        "hasRole('ADMIN') and (isAuthenticated()",
        "hasRole('ADMIN') and isAuthenticated())",
        "hasRole('ADMIN') == false",
        "hasRole('ADMIN')and(isAuthenticated()) or true",
        "hasRole('ADMINISTRATOR')",
        "isAuthenticated() and hasRole('ADMIN')",
        "hasRole('ADMIN') andIsAuthenticated()",
        "hasRole('ADMIN') and \"(\" != \"\" or true and \")\" != \"\"",
        "hasRole('ADMIN') and \"'\" != \"\" or true and \"'\" != \"\"",
        "hasRole('ADMIN') and \"x\" != \"\"",
        "hasRole('ADMIN') and {cond}"
    })
    @DisplayName("ADMIN 이 필요조건이 아닌 식은 실패한다 — permitAll · isAuthenticated · 다른 표기 · 괄호 밖 or · 삼항 · 괄호 불일치 · 큰따옴표 · 템플릿 자리표시자")
    void adminRoleNotRequired(String expression) {
        assertThat(AuthorizationExpressions.requiresAdminRole(expression)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "hasAuthority('SERVICE_order-service')",
        " hasAuthority('SERVICE_order-service') ",
        "hasAnyAuthority('SERVICE_order-service', 'SERVICE_payment-service')"
    })
    @DisplayName("서비스 권한만 나열한 식은 허용 호출자 선언이다")
    void serviceCallersOnly(String expression) {
        assertThat(AuthorizationExpressions.isServiceCallersOnly(expression)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
        "isAuthenticated()",
        "permitAll()",
        "hasAuthority('SERVICE_order-service') or permitAll()",
        "hasAnyAuthority('SERVICE_order-service', 'ROLE_ADMIN')",
        "hasRole('ADMIN')",
        "hasAuthority('SERVICE_Order')",
        "hasAuthority(\"SERVICE_order-service\")",
        "hasAuthority('SERVICE_{caller}')",
        "{callers}"
    })
    @DisplayName("서비스 권한 밖으로 넓히거나 다른 식 · 큰따옴표 · 템플릿 자리표시자면 허용 호출자 선언이 아니다")
    void notServiceCallersOnly(String expression) {
        assertThat(AuthorizationExpressions.isServiceCallersOnly(expression)).isFalse();
    }
}
