package com.commercefoundry.security.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.principal.AdminAuthority;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 런타임 가드(인가 스모크)와 정적 규칙(ArchUnit)이 같이 쓰는 {@code @PreAuthorize} 식 판정. 통과시키는 쪽이 틀리면 공개 API 가 되므로 거절 케이스를 넓게 둔다. */
class AuthorizationExpressionsTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "hasAuthority('ADMIN_ORDER_READ')",
        "  hasAuthority( 'ADMIN_ORDER_READ' )  ",
        "hasAuthority('ADMIN_ORDER_READ') and isFullyAuthenticated()",
        "hasAuthority('ADMIN_ORDER_READ') AND isFullyAuthenticated()",
        "hasAuthority('ADMIN_ORDER_READ') && isFullyAuthenticated()",
        "hasAuthority('ADMIN_ORDER_READ') and !hasAuthority('SCOPE_readonly')",
        "hasAuthority('ADMIN_ORDER_READ') and (hasAuthority('SCOPE_a') or hasAuthority('SCOPE_b'))",
        "hasAuthority('ADMIN_ORDER_READ') and hasAuthority('SCOPE_or')",
        "hasAuthority('ADMIN_ORDER_READ') and principal.name != 'operator'",
        "hasAuthority('ADMIN_ORDER_MANAGE')",
        "hasAuthority('ADMIN_CATALOG_READ')",
        "hasAuthority('ADMIN_SETTINGS_MANAGE') and isFullyAuthenticated()",
        " hasAuthority( 'ADMIN_SECURITY_READ' ) ",
        "hasAnyRole('ADMIN','OWNER','MANAGER','STAFF')",
        "hasAnyRole('ADMIN','OWNER','MANAGER','STAFF') and isFullyAuthenticated()"
    })
    @DisplayName("운영자 권한이 필요조건인 식은 통과한다 — AdminAuthority 기능 상수 · OPERATOR, 단독 · and 로 좁힘 · 괄호 · 문자열 안의 or")
    void operatorAuthorityRequired(String expression) {
        assertThat(AuthorizationExpressions.requiresOperatorAuthority(expression)).isTrue();
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
        "hasRole('ADMIN')",
        "  hasRole( 'ADMIN' )  ",
        "hasRole('ADMIN') and isFullyAuthenticated()",
        "hasRole('ADMIN') && isFullyAuthenticated()",
        "hasAuthority('ADMIN_ORDER_READ') or isAuthenticated()",
        "hasAuthority('ADMIN_ORDER_READ') OR isAuthenticated()",
        "hasAuthority('ADMIN_ORDER_READ') || isAuthenticated()",
        "hasAuthority('ADMIN_ORDER_READ') and isAuthenticated() or permitAll()",
        "hasAuthority('ADMIN_ORDER_READ') and isAuthenticated() ? true : true",
        "hasAuthority('ADMIN_ORDER_READ') and (isAuthenticated()",
        "hasAuthority('ADMIN_ORDER_READ') and isAuthenticated())",
        "hasAuthority('ADMIN_ORDER_READ') == false",
        "hasAuthority('ADMIN_ORDER_READ')and(isAuthenticated()) or true",
        "hasRole('ADMINISTRATOR')",
        "isAuthenticated() and hasAuthority('ADMIN_ORDER_READ')",
        "hasAuthority('ADMIN_ORDER_READ') andIsAuthenticated()",
        "hasAuthority('ADMIN_ORDER_READ') and \"(\" != \"\" or true and \")\" != \"\"",
        "hasAuthority('ADMIN_ORDER_READ') and \"'\" != \"\" or true and \"'\" != \"\"",
        "hasAuthority('ADMIN_ORDER_READ') and \"x\" != \"\"",
        "hasAuthority('ADMIN_ORDER_READ') and {cond}",
        "hasAuthority('ADMIN_FOO_READ')",
        "hasAuthority('ADMIN_ORDER_WRITE')",
        "hasAuthority('ADMIN_ORDER')",
        "hasAuthority('admin_order_read')",
        "hasAnyAuthority('ADMIN_ORDER_READ')",
        "hasAnyAuthority('ADMIN_ORDER_READ', 'ADMIN_ORDER_MANAGE')",
        "hasAuthority('ADMIN_ORDER_READ') or isAuthenticated()",
        "hasAuthority('ADMIN_ORDER_READ', 'ROLE_CUSTOMER')",
        "hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'STAFF')",
        "hasAnyRole('OWNER','ADMIN','MANAGER','STAFF')",
        "hasAnyRole('ADMIN','OWNER','MANAGER','STAFF','CUSTOMER')",
        "hasAnyRole('ADMIN','OWNER','MANAGER','STAFF') or permitAll()",
        "hasRole('OWNER')",
        "hasRole('MANAGER')"
    })
    @DisplayName("운영자 권한이 필요조건이 아닌 식은 실패한다 — permitAll · isAuthenticated · #236 이전 표기 hasRole('ADMIN')(#588) · 다른 표기 · 정의되지 않은 기능 · "
        + "hasAnyAuthority · OPERATOR 변형 · 괄호 밖 or · 삼항 · 괄호 불일치 · 큰따옴표 · 템플릿 자리표시자")
    void operatorAuthorityNotRequired(String expression) {
        assertThat(AuthorizationExpressions.requiresOperatorAuthority(expression)).isFalse();
    }

    @Test
    @DisplayName("AdminAuthority 의 모든 상수는 운영자 권한 식으로 통과한다 — 컨트롤러가 상수를 쓰면 R2 · 스모크 스캔이 막지 않는다")
    void everyAdminAuthorityConstantPasses() throws IllegalAccessException {
        for (Field field : AdminAuthority.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                String expression = (String) field.get(null);
                assertThat(AuthorizationExpressions.requiresOperatorAuthority(expression)).as(field.getName()).isTrue();
            }
        }
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
