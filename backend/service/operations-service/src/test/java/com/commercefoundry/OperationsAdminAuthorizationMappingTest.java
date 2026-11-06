package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.principal.AdminAuthority;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * operations-service 어드민 핸들러 → 기능 권한 표 (#588, {@code security-guide.md} §3-1). 실제 MVC 매핑의 {@code /api/v1/admin/**} 핸들러를 모두 모아
 * (HTTP 메서드, 경로) → {@code @PreAuthorize} 식을 아래 표와 양방향으로 대조한다 — 표에 없는 새 핸들러, 사라진 핸들러, 식이 바뀐 핸들러가 모두 실패다.
 * 새 어드민 핸들러를 더하면 이 표에 권한을 정해 적는다. 조회는 {@code *_READ}, 변경은 {@code *_MANAGE} 이고 {@code MANAGE} 가 {@code READ} 를 포함하지 않는다.
 *
 * <p>애노테이션은 {@code OperationsServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OperationsAdminAuthorizationMappingTest {

    private static final String ADMIN_PREFIX = "/api/v1/admin";

    /**
     * <ul>
     *   <li>캠페인 수신 대상 미리보기 = CS_READ — 회원 목록과 같은 노출 수준이고 저장 · 발송하지 않는다.</li>
     *   <li>백그라운드 작업 = REPORT. 지금 등록되는 작업 종류는 리포트 · 집계다. 정산 · 원가 · 개인정보를 담는 작업 종류가 생기면 종류별로 따로 정한다.</li>
     *   <li>법적 문서 · 상점 프로필 = SETTINGS — 공개 사업자 정보와 약관이라 변경은 대표만(SETTINGS_MANAGE).</li>
     *   <li>알림 · 온보딩 = OPERATOR — 본인 수신함 · 본인 진행만 다룬다(유스케이스가 토큰의 memberId 로 거른다).</li>
     * </ul>
     */
    private static final Map<String, String> EXPECTED = Map.ofEntries(
        // 캠페인
        Map.entry("POST /api/v1/admin/campaigns/recipient-previews", AdminAuthority.CS_READ),
        // 백그라운드 작업
        Map.entry("GET /api/v1/admin/jobs", AdminAuthority.REPORT_READ),
        Map.entry("GET /api/v1/admin/jobs/{jobId}", AdminAuthority.REPORT_READ),
        Map.entry("POST /api/v1/admin/jobs/{jobId}/cancel", AdminAuthority.REPORT_MANAGE),
        Map.entry("POST /api/v1/admin/jobs/{jobId}/result-download", AdminAuthority.REPORT_READ),
        // 법적 문서
        Map.entry("GET /api/v1/admin/store/legal-documents", AdminAuthority.SETTINGS_READ),
        Map.entry("GET /api/v1/admin/store/legal-documents/{type}/{locale}", AdminAuthority.SETTINGS_READ),
        Map.entry("PUT /api/v1/admin/store/legal-documents/{type}/{locale}/draft", AdminAuthority.SETTINGS_MANAGE),
        Map.entry("POST /api/v1/admin/store/legal-documents/{type}/{locale}/publish", AdminAuthority.SETTINGS_MANAGE),
        Map.entry("GET /api/v1/admin/store/legal-documents/{type}/{locale}/revisions", AdminAuthority.SETTINGS_READ),
        Map.entry("GET /api/v1/admin/store/legal-document-templates/{type}", AdminAuthority.SETTINGS_READ),
        // 상점 프로필
        Map.entry("GET /api/v1/admin/store/profile", AdminAuthority.SETTINGS_READ),
        Map.entry("PUT /api/v1/admin/store/profile", AdminAuthority.SETTINGS_MANAGE),
        // 알림 (본인 수신함)
        Map.entry("GET /api/v1/admin/notifications", AdminAuthority.OPERATOR),
        Map.entry("GET /api/v1/admin/notifications/unread-count", AdminAuthority.OPERATOR),
        Map.entry("POST /api/v1/admin/notifications/{notificationId}/read", AdminAuthority.OPERATOR),
        Map.entry("POST /api/v1/admin/notifications/read-all", AdminAuthority.OPERATOR),
        Map.entry("GET /api/v1/admin/notifications/stream", AdminAuthority.OPERATOR),
        // 온보딩 (본인 진행)
        Map.entry("GET /api/v1/admin/onboarding/me", AdminAuthority.OPERATOR),
        Map.entry("PATCH /api/v1/admin/onboarding/me/tours/{guideKey}", AdminAuthority.OPERATOR),
        Map.entry("PUT /api/v1/admin/onboarding/me/tours/{guideKey}", AdminAuthority.OPERATOR)
    );

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("모든 /api/v1/admin 핸들러의 @PreAuthorize 가 기능 권한 표와 같다 — 표에 없는 핸들러 · 사라진 핸들러 · 바뀐 식이 모두 실패다")
    void adminHandlersMatchAuthorityTable() {
        Map<String, String> actual = new TreeMap<>();
        handlerMapping.getHandlerMethods().forEach((mapping, handler) -> mapping.getPatternValues().stream()
            .filter(pattern -> pattern.equals(ADMIN_PREFIX) || pattern.startsWith(ADMIN_PREFIX + "/"))
            .forEach(pattern -> {
                assertThat(mapping.getMethodsCondition().getMethods()).as("%s 는 HTTP 메서드를 하나로 고정한다", pattern).hasSize(1);
                PreAuthorize preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
                String expression = preAuthorize == null ? "@PreAuthorize 없음" : preAuthorize.value();
                String key = mapping.getMethodsCondition().getMethods().iterator().next() + " " + pattern;
                assertThat(actual).as("%s 에 조건만 다른 핸들러가 둘이면 표 대조가 한쪽을 놓친다", key).doesNotContainKey(key);
                actual.put(key, expression);
            }));

        assertThat(actual).as("어드민 핸들러 → AdminAuthority (security-guide.md §3-1)").containsExactlyInAnyOrderEntriesOf(EXPECTED);
    }
}
