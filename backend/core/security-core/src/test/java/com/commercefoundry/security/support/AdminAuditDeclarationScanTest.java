package com.commercefoundry.security.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.audit.AdminAuditExempt;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport.AuditScan;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

/**
 * 관리자 감사 선언 누락 가드({@link ResourceServerSmokeTestSupport#adminWriteHandlersDeclareAudit})의 판정을 서비스 컨텍스트 없이 본다 (#163).
 * 경로 · HTTP 메서드로 대상을 고르고, 선언 · 면제 · 대기 목록을 어떻게 판정하는지 직접 만든 매핑으로 시험한다.
 */
class AdminAuditDeclarationScanTest {

    @Test
    @DisplayName("/api/v1/admin 의 POST · PUT · PATCH · DELETE · 메서드 무제한 핸들러만 보고, @AdminAudited · @AdminAuditExempt 가 없으면 고치는 법과 함께 실패한다")
    void scanJudgesAdminWrites() {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(mapping("/api/v1/admin/categories", RequestMethod.POST), handler("audited"));
        handlers.put(mapping("/api/v1/admin/displays/preview", RequestMethod.POST), handler("exempt"));
        handlers.put(mapping("/api/v1/admin/categories/{id}", RequestMethod.PATCH), handler("missing"));
        handlers.put(mapping("/api/v1/admin/categories/{id}", RequestMethod.DELETE), handler("missingDelete"));
        handlers.put(mapping("/api/v1/admin/anything"), handler("missingAnyMethod"));
        handlers.put(mapping("/api/v1/admin/both", RequestMethod.PUT), handler("both"));
        handlers.put(mapping("/api/v1/admin/blank", RequestMethod.POST), handler("blankReason"));
        handlers.put(mapping("/api/v1/admin/lower", RequestMethod.POST), handler("lowerAction"));
        handlers.put(mapping("/api/v1/admin/categories", RequestMethod.GET), handler("missingRead"));
        handlers.put(mapping("/api/v1/categories", RequestMethod.POST), handler("missingCustomer"));
        handlers.put(mapping("/api/v1/administrators", RequestMethod.POST), handler("missingLookalike"));
        handlers.put(mapping("/internal/v1/admin/x", RequestMethod.POST), handler("missingInternal"));

        AuditScan scan = ResourceServerSmokeTestSupport.scanAuditDeclarations(handlers, Map.of());

        assertThat(scan.violations())
            .hasSize(6)
            .anySatisfy(detail -> assertThat(detail).contains("Handlers#missing [/api/v1/admin/categories/{id}]", "감사 선언 없음", "@AdminAudited(action", "@AdminAuditExempt(reason"))
            .anySatisfy(detail -> assertThat(detail).contains("Handlers#missingDelete [/api/v1/admin/categories/{id}]", "감사 선언 없음"))
            .anySatisfy(detail -> assertThat(detail).contains("Handlers#missingAnyMethod [/api/v1/admin/anything]", "감사 선언 없음"))
            .anySatisfy(detail -> assertThat(detail).contains("Handlers#both [/api/v1/admin/both]", "하나만"))
            .anySatisfy(detail -> assertThat(detail).contains("Handlers#blankReason [/api/v1/admin/blank]", "reason"))
            .anySatisfy(detail -> assertThat(detail).contains("Handlers#lowerAction [/api/v1/admin/lower]", "<RESOURCE>_<VERB>"))
            .noneSatisfy(detail -> assertThat(detail).contains("#audited"))
            .noneSatisfy(detail -> assertThat(detail).contains("#exempt"))
            .noneSatisfy(detail -> assertThat(detail).contains("#missingRead"))
            .noneSatisfy(detail -> assertThat(detail).contains("#missingCustomer"))
            .noneSatisfy(detail -> assertThat(detail).contains("#missingLookalike"))
            .noneSatisfy(detail -> assertThat(detail).contains("#missingInternal"));
    }

    @Test
    @DisplayName("대기 목록(클래스#메서드 → 이슈 표기)에 있는 미선언 핸들러는 통과한다 — 한 핸들러의 여러 매핑도 한 항목으로 덮는다")
    void pendingAllowsUndeclared() {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(mapping("/api/v1/admin/categories/{id}", RequestMethod.PATCH), handler("missing"));
        handlers.put(mapping("/api/v1/admin/categories/{id}/name", RequestMethod.PUT), handler("missing"));

        AuditScan scan = ResourceServerSmokeTestSupport.scanAuditDeclarations(handlers, Map.of("Handlers#missing", "#163 PR-B"));

        assertThat(scan.violations()).isEmpty();
    }

    @Test
    @DisplayName("대기 항목의 이슈 표기에 #<번호> 가 없으면 실패한다")
    void pendingWithoutIssueFails() {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(mapping("/api/v1/admin/categories/{id}", RequestMethod.PATCH), handler("missing"));
        handlers.put(mapping("/api/v1/admin/categories/{id}", RequestMethod.DELETE), handler("missingDelete"));

        AuditScan scan = ResourceServerSmokeTestSupport.scanAuditDeclarations(handlers, Map.of("Handlers#missing", "나중에", "Handlers#missingDelete", "PR-B #"));

        assertThat(scan.violations())
            .hasSize(2)
            .anySatisfy(detail -> assertThat(detail).contains("Handlers#missing [대기]", "이슈 표기"))
            .anySatisfy(detail -> assertThat(detail).contains("Handlers#missingDelete [대기]", "이슈 표기"));
    }

    @Test
    @DisplayName("대기 항목 하나가 같은 이름의 쓰기 핸들러 둘(오버로드)과 맞으면 실패한다 — 새 핸들러가 묻어 가지 않게")
    void pendingMatchingOverloadsFails() {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(mapping("/api/v1/admin/categories/{id}", RequestMethod.PATCH), handler("missing"));
        handlers.put(mapping("/api/v1/admin/categories/{id}/name", RequestMethod.PUT), handler("missing", String.class));

        AuditScan scan = ResourceServerSmokeTestSupport.scanAuditDeclarations(handlers, Map.of("Handlers#missing", "#163 PR-B"));

        assertThat(scan.violations()).singleElement().satisfies(detail -> assertThat(detail).contains("Handlers#missing [쓰기 핸들러 2개]"));
    }

    @Test
    @DisplayName("대기 목록에 남은 항목이 이미 선언했거나 없는 핸들러면 실패한다 — 목록에서 지우라고 알린다")
    void stalePendingFails() {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(mapping("/api/v1/admin/categories", RequestMethod.POST), handler("audited"));
        handlers.put(mapping("/api/v1/admin/categories", RequestMethod.GET), handler("missingRead"));

        AuditScan scan = ResourceServerSmokeTestSupport.scanAuditDeclarations(
            handlers, Map.of("Handlers#audited", "#163 PR-B", "Handlers#gone", "#163 PR-B", "Handlers#missingRead", "#163 PR-B"));

        assertThat(scan.violations())
            .hasSize(3)
            .anySatisfy(detail -> assertThat(detail).contains("Handlers#audited [/api/v1/admin/categories]", "이미 선언", "auditDeclarationPending()"))
            .anySatisfy(detail -> assertThat(detail).contains("Handlers#gone [없음]", "auditDeclarationPending()"))
            .anySatisfy(detail -> assertThat(detail).contains("Handlers#missingRead [없음]", "auditDeclarationPending()"));
    }

    private static RequestMappingInfo mapping(String path, RequestMethod... methods) {
        return RequestMappingInfo.paths(path).methods(methods).build();
    }

    private static HandlerMethod handler(String name, Class<?>... parameterTypes) {
        Method method = ReflectionUtils.findMethod(Handlers.class, name, parameterTypes);
        assertThat(method).as(name).isNotNull();
        return new HandlerMethod(new Handlers(), method);
    }

    /** 판정에 쓰는 선언만 가진 핸들러 모음. 매핑은 {@link #mapping} 이 경로 · 메서드로 따로 준다. */
    static class Handlers {

        @AdminAudited(action = "CATEGORY_CREATE")
        public void audited() {
        }

        @AdminAuditExempt(reason = "저장 없는 미리보기")
        public void exempt() {
        }

        public void missing() {
        }

        public void missing(String name) {
        }

        public void missingDelete() {
        }

        public void missingAnyMethod() {
        }

        @AdminAudited(action = "BOTH_UPDATE")
        @AdminAuditExempt(reason = "모순")
        public void both() {
        }

        @AdminAuditExempt(reason = " ")
        public void blankReason() {
        }

        @AdminAudited(action = "category_create")
        public void lowerAction() {
        }

        public void missingRead() {
        }

        public void missingCustomer() {
        }

        public void missingLookalike() {
        }

        public void missingInternal() {
        }
    }
}
