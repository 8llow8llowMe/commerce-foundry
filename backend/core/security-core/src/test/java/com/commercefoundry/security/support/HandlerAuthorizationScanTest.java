package com.commercefoundry.security.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.principal.AdminAuthority;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport.AuthorizationScan;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

/**
 * 인가 스모크({@link ResourceServerSmokeTestSupport#handlersDeclareAuthorization})의 판정을 서비스 컨텍스트 없이 본다. 매핑은 경로만 있으면 되므로
 * 패키지 · 상속과 무관하게 경로로 판정하는지 — 특히 패키지 기준 ArchUnit R2 를 비켜 간 어드민 핸들러를 잡는지 — 를 직접 만든 매핑으로 시험한다.
 */
class HandlerAuthorizationScanTest {

    @Test
    @DisplayName("/api/v1/admin 핸들러는 운영자 권한(AdminAuthority 기능 상수 · OPERATOR)이 필요조건이어야 하고(hasRole('ADMIN') 은 #588 부터 실패), "
        + "그 밖의 /api 는 선언만, /internal 은 허용 호출자 그 자체여야 한다")
    void scanJudgesByPath() {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(mapping("/api/v1/admin/policies"), handler("legacyAdminRole"));
        handlers.put(mapping("/api/v1/admin"), handler("featureNarrowed"));
        handlers.put(mapping("/api/v1/admin/orders"), handler("featureAuthority"));
        handlers.put(mapping("/api/v1/admin/me/password"), handler("operator"));
        handlers.put(mapping("/api/v1/admin/foo"), handler("unknownFeature"));
        handlers.put(mapping("/api/v1/admin/members"), handler("authenticated"));
        handlers.put(mapping("/api/v1/admin/open"), handler("open"));
        handlers.put(mapping("/api/v1/admin/missing"), handler("missing"));
        handlers.put(mapping("/api/v1/administrators"), handler("open"));
        handlers.put(mapping("/api/v1/products"), handler("authenticated"));
        handlers.put(mapping("/api/v1/forgotten"), handler("missing"));
        handlers.put(mapping("/internal/v1/stocks"), handler("serviceCaller"));
        handlers.put(mapping("/internal/v1/widened"), handler("authenticated"));
        handlers.put(mapping("/actuator/health"), handler("missing"));

        AuthorizationScan scan = ResourceServerSmokeTestSupport.scanHandlers(handlers);

        assertThat(scan.checkedInternalPatterns()).containsExactlyInAnyOrder("/internal/v1/stocks", "/internal/v1/widened");
        assertThat(scan.violations())
            .hasSize(7)
            .anySatisfy(detail -> assertThat(detail).contains("#legacyAdminRole [/api/v1/admin/policies]", "hasRole('ADMIN')", "#588"))
            .anySatisfy(detail -> assertThat(detail).contains("#unknownFeature [/api/v1/admin/foo]", "ADMIN_FOO_READ"))
            .anySatisfy(detail -> assertThat(detail).contains("#authenticated [/api/v1/admin/members]", "isAuthenticated()", "AdminAuthority"))
            .anySatisfy(detail -> assertThat(detail).contains("#open [/api/v1/admin/open]", "permitAll()"))
            .anySatisfy(detail -> assertThat(detail).contains("#missing [/api/v1/admin/missing]", "@PreAuthorize 없음"))
            .anySatisfy(detail -> assertThat(detail).contains("#missing [/api/v1/forgotten]", "@PreAuthorize 없음"))
            .anySatisfy(detail -> assertThat(detail).contains("#authenticated [/internal/v1/widened]", "SERVICE_"))
            .noneSatisfy(detail -> assertThat(detail).contains("/api/v1/administrators"))
            .noneSatisfy(detail -> assertThat(detail).contains("/actuator"));
    }

    private static RequestMappingInfo mapping(String path) {
        return RequestMappingInfo.paths(path).build();
    }

    private static HandlerMethod handler(String name) {
        Method method = ReflectionUtils.findMethod(Handlers.class, name);
        assertThat(method).as(name).isNotNull();
        return new HandlerMethod(new Handlers(), method);
    }

    /** 판정에 쓰는 선언만 가진 핸들러 모음. 매핑은 {@link #mapping} 이 경로로 따로 준다 — 어느 패키지 · 클래스에 있든 경로로 판정함을 보인다. */
    static class Handlers {

        /** #236 이전 표기 — #588 부터 어드민 경로에서 위반이다. */
        @PreAuthorize("hasRole('ADMIN')")
        public void legacyAdminRole() {
        }

        @PreAuthorize("hasAuthority('ADMIN_ORDER_READ') and isFullyAuthenticated()")
        public void featureNarrowed() {
        }

        @PreAuthorize(AdminAuthority.ORDER_MANAGE)
        public void featureAuthority() {
        }

        @PreAuthorize(AdminAuthority.OPERATOR)
        public void operator() {
        }

        @PreAuthorize("hasAuthority('ADMIN_FOO_READ')")
        public void unknownFeature() {
        }

        @PreAuthorize("isAuthenticated()")
        public void authenticated() {
        }

        @PreAuthorize("permitAll()")
        public void open() {
        }

        public void missing() {
        }

        @PreAuthorize("hasAuthority('SERVICE_order-service')")
        public void serviceCaller() {
        }
    }
}
