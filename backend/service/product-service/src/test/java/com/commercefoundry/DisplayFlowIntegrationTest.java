package com.commercefoundry;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.display.adapter.out.persistence.repository.DisplayItemRepository;
import com.commercefoundry.domainlayer.display.adapter.out.persistence.repository.DisplaySectionRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 메인 전시 어드민 쓰기 → 고객 메인 조회를 실제 빈 구성(Facade · Processor · 어댑터 · H2)으로 잇는다 — 매퍼 · 잠금 조회 · 아이템 bulk 삭제 뒤 같은 순서 번호로 다시 넣기
 * (유니크 키와 flush 순서) · 노출 필터 · 숨긴 상품 제외를 본다. 엔진 고유 동작(check 제약 · 잠금 대기)은 PostgreSQL 리포지토리 테스트가 본다.
 */
@SpringBootTest(properties = "store.media.public-base-url=https://cdn.example.com/media/")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DisplayFlowIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ADMIN_PATH = "/api/v1/admin/displays/sections";
    private static final long VISIBLE_PRODUCT = 9_001L;
    private static final long HIDDEN_PRODUCT = 9_002L;
    private static final long OTHER_PRODUCT = 9_003L;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private DisplaySectionRepository displaySectionRepository;

    @Autowired
    private DisplayItemRepository displayItemRepository;

    @BeforeEach
    void setUp() {
        productRepository.saveAll(List.of(product(VISIBLE_PRODUCT, true), product(HIDDEN_PRODUCT, false), product(OTHER_PRODUCT, true)));
    }

    @AfterEach
    void tearDown() {
        displayItemRepository.deleteAllInBatch();
        displaySectionRepository.deleteAllInBatch();
        productRepository.deleteAllById(List.of(VISIBLE_PRODUCT, HIDDEN_PRODUCT, OTHER_PRODUCT));
    }

    @Test
    @DisplayName("생성 · 교체 · 순서 변경 · 삭제가 고객 메인에 그대로 보이고, 숨긴 상품은 고객 메인에서만 빠진다")
    void adminWritesShowOnMain() {
        String story = id(post("{\"sectionType\": \"STORY\", \"title\": \"이야기\", \"visible\": true, \"items\": []}"));
        String pick = id(post("""
            {"sectionType": "PRODUCT_PICK", "title": "픽", "visible": true, "items": [
              {"itemType": "PRODUCT", "productId": "%d"}, {"itemType": "PRODUCT", "productId": "%d"}]}
            """.formatted(VISIBLE_PRODUCT, HIDDEN_PRODUCT)));
        String hero = id(post("""
            {"sectionType": "HERO", "visible": true, "items": [
              {"itemType": "MEDIA", "media": {"mediaType": "IMAGE", "pcUrl": "https://cdn.example.com/media/displays/hero.jpg"}}]}
            """));
        post("{\"sectionType\": \"STORY\", \"visible\": false, \"items\": []}");

        JsonNode main = body(mvc.get().uri("/api/v1/displays/main").exchange());
        JsonNode sections = main.at("/dataBody/sections");
        assertThat(sections.size()).as("숨긴 섹션은 빠진다").isEqualTo(3);
        assertThat(sections.get(0).at("/sectionId").asString()).isEqualTo(story);
        assertThat(sections.get(1).at("/items").size()).as("숨긴 상품은 빠진다").isEqualTo(1);
        assertThat(sections.get(1).at("/items/0/product/productId").asString()).isEqualTo(String.valueOf(VISIBLE_PRODUCT));
        assertThat(sections.get(1).at("/items/0/product/salePrice/amount").asLong()).isEqualTo(39_000L);
        assertThat(sections.get(2).at("/items/0/media/pcUrl").asString()).isEqualTo("https://cdn.example.com/media/displays/hero.jpg");

        MvcTestResult replaced = exchange("PUT", ADMIN_PATH + "/" + pick, """
            {"sectionType": "PRODUCT_PICK", "title": "바뀐 픽", "visible": true, "items": [
              {"itemType": "PRODUCT", "productId": "%d"}, {"itemType": "PRODUCT", "productId": "%d"}]}
            """.formatted(OTHER_PRODUCT, VISIBLE_PRODUCT));
        assertThat(replaced).as("같은 순서 번호로 다시 넣어도 유니크 키에 걸리지 않는다").hasStatusOk();
        assertThat(body(replaced).at("/dataBody/items/0/product/productId").asString()).isEqualTo(String.valueOf(OTHER_PRODUCT));
        assertThat(body(replaced).has("displayOrder")).as("노출 순서는 어드민 응답에 내지 않는다(#305) — 배열 순서로 전달한다").isFalse();
        assertThat(body(replaced).at("/dataBody").has("displayOrder")).isFalse();

        JsonNode adminSections = body(exchange("GET", ADMIN_PATH, null)).at("/dataBody/sections");
        assertThat(adminSections.get(1).at("/sectionId").asString()).as("교체는 순서를 지킨다 — 목록에서 그대로 두 번째").isEqualTo(pick);
        String hidden = adminSections.get(3).at("/sectionId").asString();
        assertThat(adminSections.get(3).at("/liveStatus/code").asString()).as("숨긴 섹션").isEqualTo("HIDDEN");
        assertThat(adminSections.get(0).at("/liveStatus/code").asString()).isEqualTo("LIVE");
        assertThat(adminSections.get(1).at("/items/0/itemType/code").asString()).isEqualTo("PRODUCT");
        assertThat(adminSections.get(1).at("/items/0").has("itemId")).as("아이템 아이디는 내지 않는다").isFalse();
        MvcTestResult reordered = exchange("PATCH", ADMIN_PATH + "/order", "{\"sectionIds\": [\"%s\", \"%s\", \"%s\", \"%s\"]}".formatted(hero, pick, story, hidden));
        assertThat(reordered).hasStatusOk();
        assertThat(body(reordered).at("/dataBody/sections/0/sectionId").asString()).isEqualTo(hero);

        assertThat(exchange("DELETE", ADMIN_PATH + "/" + hero, null)).hasStatusOk();
        assertThat(exchange("DELETE", ADMIN_PATH + "/" + hero, null)).hasStatus(HttpStatus.NOT_FOUND);
        JsonNode afterDelete = body(mvc.get().uri("/api/v1/displays/main").exchange()).at("/dataBody/sections");
        assertThat(afterDelete.size()).isEqualTo(2);
        assertThat(afterDelete.get(0).at("/sectionId").asString()).isEqualTo(pick);
        assertThat(afterDelete.get(0).at("/title").asString()).isEqualTo("바뀐 픽");
    }

    @Test
    @DisplayName("노출 시각 — 나노초만 다른 시작 · 종료는 400 · PRODUCT_051, 연도 9999 초과는 400 · PRODUCT_165, 저장한 시각은 마이크로초로 잘린다")
    void periodPrecisionAndRange() {
        MvcTestResult nanosOnly = exchange("POST", ADMIN_PATH, """
            {"sectionType": "STORY", "visible": true, "startAt": "2026-11-01T00:00:00.000000001Z", "endAt": "2026-11-01T00:00:00.000000002Z", "items": []}
            """);
        MvcTestResult yearTooLate = exchange("POST", ADMIN_PATH, """
            {"sectionType": "STORY", "visible": true, "endAt": "+10000-01-01T00:00:00Z", "items": []}
            """);
        MvcTestResult truncated = exchange("POST", ADMIN_PATH, """
            {"sectionType": "STORY", "visible": true, "startAt": "2026-11-01T00:00:00.123456789Z", "endAt": "9999-12-31T23:59:59.999999Z", "items": []}
            """);

        assertThat(nanosOnly).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(nanosOnly).at("/dataHeader/resultCode").asString()).isEqualTo("PRODUCT_051");
        assertThat(yearTooLate).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(yearTooLate).at("/dataHeader/resultCode").asString()).isEqualTo("PRODUCT_165");
        assertThat(truncated).hasStatus(HttpStatus.CREATED);
        assertThat(body(truncated).at("/dataBody/startAt").asString()).isEqualTo("2026-11-01T00:00:00.123456Z");
        assertThat(displaySectionRepository.count()).isOne();
    }

    @Test
    @DisplayName("없는 상품은 400 · PRODUCT_050, 저장소 밖 미디어 주소는 400 · PRODUCT_054 이고 아무것도 저장하지 않는다")
    void rejectsUnknownProductAndForeignMedia() {
        MvcTestResult unknown = exchange("POST", ADMIN_PATH, "{\"sectionType\": \"PRODUCT_PICK\", \"visible\": true, \"items\": [{\"itemType\": \"PRODUCT\", \"productId\": 1}]}");
        MvcTestResult foreign = exchange("POST", ADMIN_PATH, """
            {"sectionType": "HERO", "visible": true, "items": [{"itemType": "MEDIA", "media": {"mediaType": "IMAGE", "pcUrl": "https://evil.example.com/a.jpg"}}]}
            """);

        assertThat(unknown).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(unknown).at("/dataHeader/resultCode").asString()).isEqualTo("PRODUCT_050");
        assertThat(foreign).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(foreign).at("/dataHeader/resultCode").asString()).isEqualTo("PRODUCT_054");
        assertThat(body(foreign).at("/dataHeader/resultMessage").asString()).doesNotContain("cdn.example.com");
        assertThat(displaySectionRepository.count()).isZero();
    }

    private MvcTestResult post(String body) {
        MvcTestResult result = exchange("POST", ADMIN_PATH, body);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return result;
    }

    private MvcTestResult exchange(String method, String uri, String body) {
        var request = mvc.method(org.springframework.http.HttpMethod.valueOf(method)).uri(uri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    private static String id(MvcTestResult result) {
        return body(result).at("/dataBody/sectionId").asString();
    }

    private static JsonNode body(MvcTestResult result) {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    private static ProductEntity product(long id, boolean visible) {
        return ProductEntity.builder()
            .id(id).categoryId(10L).name("상품 " + id).saleStatus(SaleStatus.ON_SALE).visible(visible)
            .salePriceAmount(39_000L).salePriceCurrency(KRW).customOrder(false)
            .build();
    }
}
