package com.commercefoundry;

import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertEnvelope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport;
import com.commercefoundry.security.support.TestTokens;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 인가 스모크 — security-core Resource Server 가 이 서비스에 실제로 걸렸는지 본다 ({@code security-guide.md} §5-3).
 * 애노테이션은 {@link ProductServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다.
 *
 * <p>공개 조회 API 케이스는 슬라이스가 아니라 실제 빈 구성(Facade · Processor · 어댑터 · H2)으로 {@code permitAll()} 이 걸렸는지 본다.
 * internal API 의 서비스 토큰 · 익명 응답은 여기서 단언하지 않는다 — 상속한 공통 스모크({@code ResourceServerSmokeTestSupport})가 본다.
 */
class ProductServiceSecuritySmokeTest extends ResourceServerSmokeTestSupport {

    /** 감사 선언(#163)을 아직 붙이지 않은 어드민 쓰기 핸들러 — 맡은 PR 에서 선언을 붙이고 지운다 ({@code security-guide.md} §11-4). */
    @Override
    protected Map<String, String> auditDeclarationPending() {
        return Map.ofEntries(
            Map.entry("DisplayAdminController#createSection", "#163 PR-C"),
            Map.entry("DisplayAdminController#updateSection", "#163 PR-C"),
            Map.entry("DisplayAdminController#deleteSection", "#163 PR-C"),
            Map.entry("DisplayAdminController#reorderSections", "#163 PR-C"),
            Map.entry("DisplayPreviewAdminController#preview", "#163 PR-C (면제 대상 — 저장 없는 미리보기)"),
            Map.entry("LookAdminController#create", "#163 PR-C"),
            Map.entry("LookAdminController#update", "#163 PR-C"),
            Map.entry("LookAdminController#delete", "#163 PR-C"),
            Map.entry("LookAdminController#reorder", "#163 PR-C"),
            Map.entry("StockAdminController#adjustStock", "#163 PR-C′"),
            Map.entry("SkuCostAdminController#createCost", "#163 PR-C′"),
            Map.entry("ProductDraftAdminController#create", "#163 PR-C′"),
            Map.entry("ProductDraftAdminController#reject", "#163 PR-C′"),
            Map.entry("ProductAdminController#create", "#9 — 머지 뒤 #163 PR-F"),
            Map.entry("ProductAdminController#issueImageUpload", "#9 — 머지 뒤 #163 PR-F")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/categories", "/api/v1/products", "/api/v1/products?categoryId=1&sortType=PRICE_ASC", "/api/v1/displays/main", "/api/v1/looks"})
    @DisplayName("공개 조회 API 는 익명 · 회원 모두 200 이다 (permitAll)")
    void publicCatalogIsOpen(String path) throws Exception {
        assertThat(perform(get(path)).getStatus()).as("익명 " + path).isEqualTo(200);
        assertThat(perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))).getStatus()).as("회원 " + path).isEqualTo(200);
    }

    @Test
    @DisplayName("공개 상세는 익명이어도 인증 오류가 아니라 도메인 404 · PRODUCT_001 이다")
    void publicDetailReachesDomain() throws Exception {
        assertEnvelope(perform(get("/api/v1/products/1")), 404, "PRODUCT_001", "존재하지 않는 상품입니다.");
    }

    @Test
    @DisplayName("고객 룩 3경로는 익명으로 도메인까지 닿는다(permitAll) — 목록 200 · 상세 404 LOOK_001 · 없는 상품의 룩 404 PRODUCT_001, 목록은 공유 캐시 헤더를 지킨다")
    void publicLooksReachDomain() throws Exception {
        assertThat(perform(get("/api/v1/looks")).getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=60, public");
        assertEnvelope(perform(get("/api/v1/looks/1")), 404, "LOOK_001", "존재하지 않는 룩입니다.");
        assertEnvelope(perform(get("/api/v1/products/1/looks")), 404, "PRODUCT_001", "존재하지 않는 상품입니다.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/looks", "/api/v1/admin/looks/1"})
    @DisplayName("어드민 룩 경로는 익명 401 · SECURITY_001, CUSTOMER 403 · SECURITY_005 다")
    void adminLooksRequireAdmin(String path) throws Exception {
        assertEnvelope(perform(get(path)), SecurityErrorCode.UNAUTHORIZED);
        assertEnvelope(perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))), SecurityErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("어드민 룩 쓰기(순서 변경)도 CUSTOMER 는 403 · SECURITY_005 다 — 본문이 맞아도 인가에서 끊긴다")
    void adminLookWriteForbiddenForCustomer() throws Exception {
        assertEnvelope(
            perform(patch("/api/v1/admin/looks/order").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"lookIds\": []}")),
            SecurityErrorCode.FORBIDDEN
        );
    }


    @Test
    @DisplayName("어드민 카테고리 생성은 CUSTOMER 403 이면 유스케이스에 닿지 않아 감사 줄이 없고, ADMIN 의 업무 실패(없는 상위 400 · PRODUCT_044)는 FAILURE 한 줄이다")
    void categoryCreateAuditOnlyAfterAuthorization() throws Exception {
        String body = "{\"parentId\": \"999999\", \"name\": \"상의\", \"displayOrder\": 0, \"visible\": true}";
        ListAppender<ILoggingEvent> audit = new ListAppender<>();
        Logger auditLogger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
        audit.start();
        auditLogger.addAppender(audit);
        try {
            assertEnvelope(
                perform(post("/api/v1/admin/categories").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))
                    .contentType(MediaType.APPLICATION_JSON).content(body)),
                SecurityErrorCode.FORBIDDEN
            );
            assertThat(audit.list).as("거부된 요청은 유스케이스(감사)에 닿지 않는다").noneMatch(event -> event.getFormattedMessage().contains("action=CATEGORY_CREATE"));

            assertEnvelope(
                perform(post("/api/v1/admin/categories").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
                    .contentType(MediaType.APPLICATION_JSON).content(body)),
                400, "PRODUCT_044", "상위 카테고리가 존재하지 않습니다."
            );
            // 캡처가 실제로 줄을 잡는다는 증거 — 위 noneMatch 가 거짓 통과하지 않게 지우지 않는다
            assertThat(audit.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("action=CATEGORY_CREATE actorMemberId=" + TestTokens.MEMBER_ID + " target=- result=FAILURE");
        } finally {
            auditLogger.detachAppender(audit);
        }
    }
    @Test
    @DisplayName("ADMIN 은 실제 빈 구성(Facade · Processor · 어댑터 · H2)으로 룩 도메인까지 닿는다 — 목록 200, 없는 룩 404 · LOOK_001")
    void adminLooksReachDomain() throws Exception {
        assertThat(perform(get("/api/v1/admin/looks").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))).getStatus()).isEqualTo(200);
        assertEnvelope(perform(get("/api/v1/admin/looks/1").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))), 404, "LOOK_001", "존재하지 않는 룩입니다.");
    }

    @Test
    @DisplayName("메인 전시의 Cache-Control(max-age=60, public)이 보안 필터의 캐시 금지 헤더에 덮이지 않는다 — 어드민 응답에는 공유 캐시 허용이 없다")
    void mainDisplayKeepsCacheControl() throws Exception {
        assertThat(perform(get("/api/v1/displays/main")).getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=60, public");
        String adminCacheControl = perform(get("/api/v1/admin/displays/sections").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))).getHeader(HttpHeaders.CACHE_CONTROL);
        assertThat(String.valueOf(adminCacheControl)).doesNotContain("public");
    }
}
