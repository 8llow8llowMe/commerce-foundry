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
 * product-service 어드민 핸들러 → 기능 권한 표 (#586, {@code security-guide.md} §3-1). 실제 MVC 매핑의 {@code /api/v1/admin/**} 핸들러를 모두 모아
 * (HTTP 메서드, 경로) → {@code @PreAuthorize} 식을 아래 표와 양방향으로 대조한다 — 표에 없는 새 핸들러, 사라진 핸들러, 식이 바뀐 핸들러가 모두 실패다.
 * 새 어드민 핸들러를 더하면 이 표에 권한을 정해 적는다. 조회는 {@code *_READ}, 변경은 {@code *_MANAGE} 이고 {@code MANAGE} 가 {@code READ} 를 포함하지 않는다.
 *
 * <p>애노테이션은 {@code ProductServiceApplicationTests} · {@code ProductServiceSecuritySmokeTest} 와 같아 컨텍스트를 같이 쓴다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ProductAdminAuthorizationMappingTest {

    private static final String ADMIN_PREFIX = "/api/v1/admin";

    /** 상품 · 카테고리 · 사이즈표 · 초안 · 업로드 · 전시 · 룩 · SKU 구조 = CATALOG, 재고 · 품절 임박 = INVENTORY, 원가 = COST. */
    private static final Map<String, String> EXPECTED = Map.ofEntries(
        // 상품
        Map.entry("GET /api/v1/admin/products", AdminAuthority.CATALOG_READ),
        Map.entry("GET /api/v1/admin/products/{productId}", AdminAuthority.CATALOG_READ),
        Map.entry("POST /api/v1/admin/products", AdminAuthority.CATALOG_MANAGE),
        Map.entry("POST /api/v1/admin/products/image-uploads", AdminAuthority.CATALOG_MANAGE),
        Map.entry("PUT /api/v1/admin/products/{productId}/options", AdminAuthority.CATALOG_MANAGE),
        Map.entry("PUT /api/v1/admin/products/{productId}/custom-order", AdminAuthority.CATALOG_MANAGE),
        Map.entry("PUT /api/v1/admin/products/{productId}/images", AdminAuthority.CATALOG_MANAGE),
        Map.entry("PUT /api/v1/admin/products/{productId}/pricing", AdminAuthority.CATALOG_MANAGE),
        Map.entry("PUT /api/v1/admin/products/{productId}/categories", AdminAuthority.CATALOG_MANAGE),
        Map.entry("PUT /api/v1/admin/products/{productId}/header", AdminAuthority.CATALOG_MANAGE),
        Map.entry("DELETE /api/v1/admin/products/{productId}", AdminAuthority.CATALOG_MANAGE),
        // SKU 구조(옵션 · SKU 조합) — 수량 변경(/stocks)이 아니라 상품 구조라 CATALOG
        Map.entry("PUT /api/v1/admin/products/{productId}/skus", AdminAuthority.CATALOG_MANAGE),
        Map.entry("POST /api/v1/admin/products/{productId}/skus", AdminAuthority.CATALOG_MANAGE),
        Map.entry("POST /api/v1/admin/products/{productId}/option-retirements", AdminAuthority.CATALOG_MANAGE),
        // 카테고리
        Map.entry("GET /api/v1/admin/categories", AdminAuthority.CATALOG_READ),
        Map.entry("POST /api/v1/admin/categories", AdminAuthority.CATALOG_MANAGE),
        Map.entry("PUT /api/v1/admin/categories/{categoryId}", AdminAuthority.CATALOG_MANAGE),
        Map.entry("DELETE /api/v1/admin/categories/{categoryId}", AdminAuthority.CATALOG_MANAGE),
        // 사이즈표
        Map.entry("GET /api/v1/admin/products/{productId}/size-chart", AdminAuthority.CATALOG_READ),
        Map.entry("PUT /api/v1/admin/products/{productId}/size-chart", AdminAuthority.CATALOG_MANAGE),
        Map.entry("GET /api/v1/admin/size-chart-templates", AdminAuthority.CATALOG_READ),
        Map.entry("GET /api/v1/admin/size-chart-templates/{templateId}", AdminAuthority.CATALOG_READ),
        Map.entry("POST /api/v1/admin/size-chart-templates", AdminAuthority.CATALOG_MANAGE),
        Map.entry("PUT /api/v1/admin/size-chart-templates/{templateId}", AdminAuthority.CATALOG_MANAGE),
        Map.entry("DELETE /api/v1/admin/size-chart-templates/{templateId}", AdminAuthority.CATALOG_MANAGE),
        // AI 상품 초안
        Map.entry("GET /api/v1/admin/product-drafts/{draftId}", AdminAuthority.CATALOG_READ),
        Map.entry("POST /api/v1/admin/product-drafts", AdminAuthority.CATALOG_MANAGE),
        Map.entry("POST /api/v1/admin/product-drafts/{draftId}/reject", AdminAuthority.CATALOG_MANAGE),
        // 전시 · 미리보기 · 이미지 업로드
        Map.entry("GET /api/v1/admin/displays/sections", AdminAuthority.CATALOG_READ),
        Map.entry("GET /api/v1/admin/displays/sections/{sectionId}", AdminAuthority.CATALOG_READ),
        Map.entry("POST /api/v1/admin/displays/sections", AdminAuthority.CATALOG_MANAGE),
        Map.entry("PUT /api/v1/admin/displays/sections/{sectionId}", AdminAuthority.CATALOG_MANAGE),
        Map.entry("DELETE /api/v1/admin/displays/sections/{sectionId}", AdminAuthority.CATALOG_MANAGE),
        Map.entry("PATCH /api/v1/admin/displays/sections/order", AdminAuthority.CATALOG_MANAGE),
        Map.entry("POST /api/v1/admin/displays/preview", AdminAuthority.CATALOG_MANAGE),
        Map.entry("POST /api/v1/admin/displays/image-uploads", AdminAuthority.CATALOG_MANAGE),
        Map.entry("POST /api/v1/admin/displays/image-uploads/{uploadKey}/confirm", AdminAuthority.CATALOG_MANAGE),
        // 룩
        Map.entry("GET /api/v1/admin/looks", AdminAuthority.CATALOG_READ),
        Map.entry("GET /api/v1/admin/looks/{lookId}", AdminAuthority.CATALOG_READ),
        Map.entry("POST /api/v1/admin/looks", AdminAuthority.CATALOG_MANAGE),
        Map.entry("PUT /api/v1/admin/looks/{lookId}", AdminAuthority.CATALOG_MANAGE),
        Map.entry("DELETE /api/v1/admin/looks/{lookId}", AdminAuthority.CATALOG_MANAGE),
        Map.entry("PATCH /api/v1/admin/looks/order", AdminAuthority.CATALOG_MANAGE),
        // 재고 · 품절 임박
        Map.entry("GET /api/v1/admin/stocks", AdminAuthority.INVENTORY_READ),
        Map.entry("GET /api/v1/admin/stocks/{skuId}/adjustments", AdminAuthority.INVENTORY_READ),
        Map.entry("PATCH /api/v1/admin/stocks/{skuId}", AdminAuthority.INVENTORY_MANAGE),
        Map.entry("GET /api/v1/admin/stocks/{skuId}/low-stock-policy", AdminAuthority.INVENTORY_READ),
        Map.entry("PUT /api/v1/admin/stocks/{skuId}/low-stock-policy", AdminAuthority.INVENTORY_MANAGE),
        Map.entry("DELETE /api/v1/admin/stocks/{skuId}/low-stock-policy", AdminAuthority.INVENTORY_MANAGE),
        Map.entry("GET /api/v1/admin/stocks/low", AdminAuthority.INVENTORY_READ),
        // 원가
        Map.entry("GET /api/v1/admin/skus/{skuId}/costs", AdminAuthority.COST_READ),
        Map.entry("GET /api/v1/admin/skus/{skuId}/costs/histories", AdminAuthority.COST_READ),
        Map.entry("POST /api/v1/admin/skus/{skuId}/costs", AdminAuthority.COST_MANAGE)
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
                actual.put(mapping.getMethodsCondition().getMethods().iterator().next() + " " + pattern, expression);
            }));

        assertThat(actual).as("어드민 핸들러 → AdminAuthority (security-guide.md §3-1)").containsExactlyInAnyOrderEntriesOf(EXPECTED);
    }
}
