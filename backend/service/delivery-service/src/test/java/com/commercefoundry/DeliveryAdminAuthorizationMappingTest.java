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
 * delivery-service 어드민 핸들러 → 기능 권한 표 (#588, {@code security-guide.md} §3-1). 실제 MVC 매핑의 {@code /api/v1/admin/**} 핸들러를 모두 모아
 * (HTTP 메서드, 경로) → {@code @PreAuthorize} 식을 아래 표와 양방향으로 대조한다 — 표에 없는 새 핸들러, 사라진 핸들러, 식이 바뀐 핸들러가 모두 실패다.
 * 새 어드민 핸들러를 더하면 이 표에 권한을 정해 적는다. 조회는 {@code *_READ}, 변경은 {@code *_MANAGE} 이고 {@code MANAGE} 가 {@code READ} 를 포함하지 않는다.
 *
 * <p>애노테이션은 {@code DeliveryServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DeliveryAdminAuthorizationMappingTest {

    private static final String ADMIN_PREFIX = "/api/v1/admin";

    /**
     * 출고 · 배송 = FULFILLMENT. 배송비 정책 교체는 고객 결제 금액을 바꾸는 상점 설정이라 SETTINGS_MANAGE(대표만)이고, 조회는 출고 업무에 필요해 FULFILLMENT_READ 다.
     */
    private static final Map<String, String> EXPECTED = Map.ofEntries(
        // 송장 · 배송
        Map.entry("POST /api/v1/admin/deliveries", AdminAuthority.FULFILLMENT_MANAGE),
        Map.entry("GET /api/v1/admin/deliveries", AdminAuthority.FULFILLMENT_READ),
        Map.entry("GET /api/v1/admin/deliveries/{deliveryId}", AdminAuthority.FULFILLMENT_READ),
        Map.entry("PATCH /api/v1/admin/deliveries/{deliveryId}/status", AdminAuthority.FULFILLMENT_MANAGE),
        // 국내 배송비 정책
        Map.entry("GET /api/v1/admin/shipping-policies/domestic", AdminAuthority.FULFILLMENT_READ),
        Map.entry("PUT /api/v1/admin/shipping-policies/domestic", AdminAuthority.SETTINGS_MANAGE),
        Map.entry("GET /api/v1/admin/shipping-policies/domestic/histories", AdminAuthority.FULFILLMENT_READ),
        // 해외 배송비 정책
        Map.entry("GET /api/v1/admin/shipping-policies/international", AdminAuthority.FULFILLMENT_READ),
        Map.entry("PUT /api/v1/admin/shipping-policies/international", AdminAuthority.SETTINGS_MANAGE)
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
