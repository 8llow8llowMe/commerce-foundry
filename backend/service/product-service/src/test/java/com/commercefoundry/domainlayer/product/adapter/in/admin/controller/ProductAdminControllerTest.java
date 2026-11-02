package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductListAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductCreateAdminResponse;
import com.commercefoundry.domainlayer.product.application.command.ProductAdminSearchCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductCreateCommand;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSortType;
import com.commercefoundry.domainlayer.product.application.model.ProductAvailabilityFilter;
import com.commercefoundry.domainlayer.product.application.model.ProductSaleStatusFilter;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 어드민 상품 목록의 HTTP 계약 — 권한(ADMIN 만), 검증 오류 코드, 응답 모양(PageResponse · 아이디 String · 금액 · enum 메타데이터).
 *
 * <p>슬라이스라 {@code *BeansConfig} 가 빠지므로 보안 구성을 직접 import 하고, 테스트 키로 서명한 실제 access 토큰을 보낸다 ({@code security-guide.md} §5-3).
 */
@WebMvcTest(ProductAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class ProductAdminControllerTest {

    private static final String PATH = "/api/v1/admin/products";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ProductAdminUseCase productAdminUseCase;

    @Test
    @DisplayName("ADMIN 이면 200 — PageResponse 모양이고 아이디는 String, 금액은 {amount, currency}, enum 은 {code, name, description}, 시각은 UTC ISO 다")
    void adminListContract() {
        ProductListAdminItem item = ProductListAdminItem.builder()
            .productId("4611686018427387904")
            .name("숨김 저고리")
            .thumbnailUrl(null)
            .salePrice(MoneyItem.of(129_000, "KRW"))
            .listPrice(null)
            .saleStatus(CodeNameDescriptionMetadata.of("ON_SALE", "판매중", "설명"))
            .visible(false)
            .availability(CodeNameDescriptionMetadata.of("SOLD_OUT", "품절", "재고가 모두 소진되었습니다."))
            .skuCount(6)
            .updatedAt(Instant.parse("2026-10-18T09:00:00Z"))
            .build();
        given(productAdminUseCase.searchProducts(any())).willReturn(new PageResponse<>(List.of(item), 1, 20, 21, 2));

        MvcTestResult result = mvc.get().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.page").isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.dataBody.size").isEqualTo(20);
        assertThat(result).bodyJson().extractingPath("$.dataBody.totalElements").isEqualTo(21);
        assertThat(result).bodyJson().extractingPath("$.dataBody.totalPages").isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].productId").isEqualTo("4611686018427387904");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].name").isEqualTo("숨김 저고리");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].thumbnailUrl").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].salePrice.amount").isEqualTo(129_000);
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].salePrice.currency").isEqualTo("KRW");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].listPrice").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].saleStatus.code").isEqualTo("ON_SALE");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].visible").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].availability.code").isEqualTo("SOLD_OUT");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].availability.name").isEqualTo("품절");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].skuCount").isEqualTo(6);
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].updatedAt").isEqualTo("2026-10-18T09:00:00Z");
    }

    @Test
    @DisplayName("조건을 주지 않으면 전부 null 이고 정렬 NEWEST · page 0 · size 20 이다")
    void defaults() {
        givenEmptyPage();

        assertThat(adminGet(PATH)).hasStatusOk();

        verify(productAdminUseCase).searchProducts(
            ProductAdminSearchCommand.builder().sortType(ProductAdminSortType.NEWEST).page(0).size(20).build()
        );
    }

    @Test
    @DisplayName("조건을 그대로 command 로 넘긴다 — 검색어 원문 · 문자열 categoryId · enum · 경계값(100자 · size 100)")
    void passesFilters() {
        givenEmptyPage();
        String keyword = "가".repeat(100);

        MvcTestResult result = mvc.get().uri(PATH)
            .param("keyword", keyword).param("categoryId", "101").param("saleStatus", "STOP_SELLING").param("visible", "false")
            .param("availability", "SOLD_OUT").param("sortType", "NAME_ASC").param("page", "3").param("size", "100")
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
            .exchange();

        assertThat(result).hasStatusOk();

        verify(productAdminUseCase).searchProducts(ProductAdminSearchCommand.builder()
            .keyword(keyword)
            .categoryId(101L)
            .saleStatus(ProductSaleStatusFilter.STOP_SELLING)
            .visible(false)
            .availability(ProductAvailabilityFilter.SOLD_OUT)
            .sortType(ProductAdminSortType.NAME_ASC)
            .page(3)
            .size(100)
            .build());
    }

    @Test
    @DisplayName("CUSTOMER 면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
    void customerIsForbidden() {
        SecurityEnvelopeAssertions.assertEnvelope(
            mvc.get().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange().getResponse(), SecurityErrorCode.FORBIDDEN
        );
        verifyNoInteractions(productAdminUseCase);
    }

    @Test
    @DisplayName("토큰이 없으면 401 · SECURITY_001 이고 유스케이스를 부르지 않는다")
    void anonymousIsUnauthorized() {
        SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(PATH).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        verifyNoInteractions(productAdminUseCase);
    }

    @ParameterizedTest(name = "{0}={1} → {2}")
    @CsvSource({
        "keyword, 101자, PRODUCT_139, keyword 는 100자 이하여야 합니다.",
        "categoryId, 0, PRODUCT_137, categoryId 는 양수여야 합니다.",
        "categoryId, -1, PRODUCT_137, categoryId 는 양수여야 합니다.",
        "page, -1, PRODUCT_101, page 는 0 이상이어야 합니다.",
        "size, 0, PRODUCT_102, size 는 1 이상 100 이하여야 합니다.",
        "size, 101, PRODUCT_102, size 는 1 이상 100 이하여야 합니다.",
        "categoryId, abc, PRODUCT_198, categoryId 파라미터 형식이 올바르지 않습니다.",
        "saleStatus, SOLD_OUT, PRODUCT_198, saleStatus 파라미터 형식이 올바르지 않습니다.",
        "availability, HIDDEN, PRODUCT_198, availability 파라미터 형식이 올바르지 않습니다.",
        "sortType, PRICE_ASC, PRODUCT_198, sortType 파라미터 형식이 올바르지 않습니다.",
        "visible, maybe, PRODUCT_198, visible 파라미터 형식이 올바르지 않습니다.",
        "page, abc, PRODUCT_198, page 파라미터 형식이 올바르지 않습니다."
    })
    @DisplayName("잘못된 조건은 400 · 필드별 코드이고 fieldErrors 가 그 파라미터를 짚으며 유스케이스를 부르지 않는다")
    void invalidParameter(String name, String value, String code, String message) {
        String actualValue = "101자".equals(value) ? "가".repeat(101) : value;

        MvcTestResult result = mvc.get().uri(PATH).param(name, actualValue).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo(message);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(name);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        verifyNoInteractions(productAdminUseCase);
    }

    @Test
    @DisplayName("여러 조건이 함께 틀리면 모두 알린다")
    void multipleInvalid() {
        MvcTestResult result = mvc.get().uri(PATH)
            .param("keyword", "a".repeat(101)).param("categoryId", "0").param("page", "-1").param("size", "0")
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
            .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[*].code").asArray()
            .containsExactlyInAnyOrder("PRODUCT_139", "PRODUCT_137", "PRODUCT_101", "PRODUCT_102");
        verifyNoInteractions(productAdminUseCase);
    }

    @Test
    void adminDetailUsesSeparateEditorContract() {
        var response = com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductDetailAdminResponse.builder()
            .productId("4611686018427387904").visible(false).version(7L).categoryIds(List.of("1"))
            .optionGroups(List.of()).skus(List.of()).images(List.of()).customOrderFields(List.of()).build();
        given(productAdminUseCase.getProduct(4611686018427387904L)).willReturn(response);
        MvcTestResult result = adminGet(PATH + "/4611686018427387904");
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.productId").isEqualTo("4611686018427387904");
        assertThat(result).bodyJson().extractingPath("$.dataBody.visible").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataBody.version").isEqualTo(7);
        verify(productAdminUseCase).getProduct(4611686018427387904L);
    }

    @Test
    void detailRejectsCustomerAndAnonymous() {
        SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(PATH + "/1").exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(PATH + "/1")
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(productAdminUseCase);
    }

    @ParameterizedTest
    @CsvSource({"0, PRODUCT_105", "-1, PRODUCT_105", "abc, PRODUCT_198", "9223372036854775808, PRODUCT_198"})
    void detailRejectsInvalidId(String id, String code) {
        MvcTestResult result = adminGet(PATH + "/" + id);
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(productAdminUseCase);
    }

    @Test
    void detailMissingUsesDomain404Envelope() {
        given(productAdminUseCase.getProduct(1)).willThrow(new com.commercefoundry.domainlayer.product.application.exception.ProductException(
            com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode.NOT_FOUND_PRODUCT));
        MvcTestResult result = adminGet(PATH + "/1");
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_001");
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    private static final String CREATE_BODY = """
        {"name": "  린넨 저고리  ", "summary": "   ", "descriptionHtml": "<p>본문</p>", "categoryId": "12", "categoryIds": ["30"],
         "saleStatus": "ON_SALE", "visible": false, "salePrice": {"amount": 59000, "currency": "KRW"}, "preorder": true, "expectedShipDate": "2099-12-01",
         "optionGroups": [{"name": " 사이즈 ", "values": [" S ", "M"]}],
         "skus": [{"optionValues": ["S"], "additionalPrice": {"amount": 0, "currency": "KRW"}, "saleStatus": "ON_SALE", "hsCode": "6211.42-10 00",
                   "initialStock": {"stockManaged": true, "quantity": 30}},
                  {"optionValues": ["M"], "additionalPrice": {"amount": 0, "currency": "KRW"}, "saleStatus": "STOP_SELLING"}]}
        """;

    /** 유효한 최소 본문(닫는 괄호 없음) — 검증 테스트가 칸 하나를 뒤에 덧붙여 덮는다. */
    private static final String MINIMAL_BODY_PREFIX = "{\"name\": \"저고리\", \"categoryId\": 12, \"saleStatus\": \"ON_SALE\", \"visible\": false, "
        + "\"salePrice\": {\"amount\": 59000, \"currency\": \"KRW\"}, "
        + "\"skus\": [{\"optionValues\": [], \"additionalPrice\": {\"amount\": 0, \"currency\": \"KRW\"}, \"saleStatus\": \"ON_SALE\"}]";

    @Test
    @DisplayName("등록 — 최소 본문(옵션 없음 · SKU 하나)은 201 이다")
    void createMinimal() {
        given(productAdminUseCase.create(any())).willReturn(ProductCreateAdminResponse.builder().build());

        assertThat(adminPost(MINIMAL_BODY_PREFIX + "}")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void imagesUseAuthenticatedActorAndNormalizeAlternativeText() {
        given(productAdminUseCase.create(any())).willReturn(ProductCreateAdminResponse.builder().build());
        assertThat(adminPost(MINIMAL_BODY_PREFIX + ", \"createdByMemberId\": 123, \"images\":[{\"uploadKey\":\"uploads/99/token.png\","
            + "\"imageType\":\"GALLERY\",\"altText\":\"  상세  \"}]}")).hasStatus(HttpStatus.CREATED);
        var command = ArgumentCaptor.forClass(ProductCreateCommand.class);
        verify(productAdminUseCase).create(command.capture());
        assertThat(command.getValue().createdByMemberId()).isEqualTo(TestTokens.MEMBER_ID);
        assertThat(command.getValue().images().getFirst().altText()).isEqualTo("상세");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"[null]", "[{\"imageType\":\"GALLERY\"}]", "[{\"uploadKey\":\"key\"}]"})
    void imagesValidationUsesDedicatedCode(String images) {
        var result = adminPost(MINIMAL_BODY_PREFIX + ", \"images\":" + images + "}");
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_019");
        verifyNoInteractions(productAdminUseCase);
    }

    @Test
    @DisplayName("등록 — 201, 등록자는 토큰의 memberId, 문구 · 옵션 이름은 앞뒤 공백을 빼고 빈 요약은 null, HS 코드는 구분자를 뺀다. 응답은 상세 + 정제 여부")
    void create() {
        var detail = com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductDetailAdminResponse.builder()
            .productId("7").visible(false).version(0L).categoryIds(List.of("12", "30"))
            .optionGroups(List.of()).skus(List.of()).images(List.of()).customOrderFields(List.of()).build();
        given(productAdminUseCase.create(any())).willReturn(ProductCreateAdminResponse.builder().product(detail).descriptionHtmlSanitized(true).build());

        MvcTestResult result = adminPost(CREATE_BODY);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.dataBody.product.productId").isEqualTo("7");
        assertThat(result).bodyJson().extractingPath("$.dataBody.descriptionHtmlSanitized").isEqualTo(true);
        ArgumentCaptor<ProductCreateCommand> command = ArgumentCaptor.forClass(ProductCreateCommand.class);
        verify(productAdminUseCase).create(command.capture());
        ProductCreateCommand captured = command.getValue();
        assertThat(captured.createdByMemberId()).isEqualTo(TestTokens.MEMBER_ID);
        assertThat(captured.name()).isEqualTo("린넨 저고리");
        assertThat(captured.summary()).isNull();
        assertThat(captured.categoryId()).isEqualTo(12L);
        assertThat(captured.categoryIds()).containsExactly(30L);
        assertThat(captured.saleStatus()).isEqualTo(SaleStatus.ON_SALE);
        assertThat(captured.preorder()).isTrue();
        assertThat(captured.optionGroups().getFirst()).isEqualTo(new ProductCreateCommand.OptionGroup("사이즈", List.of("S", "M")));
        assertThat(captured.skus().getFirst().hsCode()).isEqualTo("6211421000");
        assertThat(captured.skus().getFirst().initialStock()).isEqualTo(new ProductCreateCommand.InitialStock(true, 30));
        assertThat(captured.skus().get(1).initialStock()).isNull();
    }

    @Test
    @DisplayName("등록 — CUSTOMER 는 403 · 토큰 없으면 401 이고 유스케이스를 부르지 않는다")
    void createRejectsCustomerAndAnonymous() {
        SecurityEnvelopeAssertions.assertEnvelope(mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY).exchange().getResponse(),
            SecurityErrorCode.UNAUTHORIZED);
        SecurityEnvelopeAssertions.assertEnvelope(mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY)
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(productAdminUseCase);
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
        "\"name\": \"   \"|PRODUCT_074",
        "\"name\": \"a\\u200Bb\"|PRODUCT_076",
        "\"categoryId\": null|PRODUCT_079",
        "\"categoryId\": 0|PRODUCT_137",
        "\"saleStatus\": null|PRODUCT_082",
        "\"salePrice\": {\"amount\": -1, \"currency\": \"KRW\"}|PRODUCT_086",
        "\"maxQuantityPerOrder\": 1000|PRODUCT_088",
        "\"preorder\": false|PRODUCT_089",
        "\"optionGroups\": [{\"name\": \"사이즈\", \"values\": []}]|PRODUCT_093",
        "\"skus\": []|PRODUCT_095",
        "\"skus\": [{\"optionValues\": [], \"additionalPrice\": {\"amount\": 0, \"currency\": \"KRW\"}, \"saleStatus\": \"ON_SALE\", \"hsCode\": \"６２１１４２\"}]|PRODUCT_099",
        "\"skus\": [{\"optionValues\": [], \"additionalPrice\": {\"amount\": 0, \"currency\": \"KRW\"}, \"saleStatus\": \"ON_SALE\", \"initialStock\": {\"quantity\": 3}}]|PRODUCT_169",
        "\"salePrice\": {\"amount\": 100000001, \"currency\": \"KRW\"}|PRODUCT_037",
        "\"preorder\": true|PRODUCT_038",
        "\"categoryIds\": [12, null]|PRODUCT_081",
        "\"skus\": [{\"optionValues\": [null], \"additionalPrice\": {\"amount\": 0, \"currency\": \"KRW\"}, \"saleStatus\": \"ON_SALE\"}]|PRODUCT_097",
        "\"saleStatus\": \"SOLD_OUT\"|PRODUCT_100"
    })
    @DisplayName("등록 검증 실패는 400 · 필드 코드이고 유스케이스를 부르지 않는다 — 본문 칸 하나를 바꿔 본다")
    void createValidation(String override, String code) {
        // 최소 유효 본문 뒤에 같은 키를 다시 적어 덮는다 — 같은 키는 마지막 값이 쓰인다
        MvcTestResult result = adminPost(MINIMAL_BODY_PREFIX + ", " + override + ", \"expectedShipDate\": " + shipDateFor(code) + "}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(productAdminUseCase);
    }

    @Test
    @DisplayName("등록 도메인 오류는 봉투로 — 없는 카테고리 400 · PRODUCT_012")
    void createDomainError() {
        given(productAdminUseCase.create(any())).willThrow(new com.commercefoundry.domainlayer.product.application.exception.ProductException(
            com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode.PRODUCT_CATEGORY_NOT_FOUND, "30"));

        MvcTestResult result = adminPost(CREATE_BODY);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_012");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("존재하지 않는 카테고리가 있습니다: 30");
    }

    /** 089 는 프리오더 아닌데 날짜, 038 은 프리오더인데 지난 날짜다. 미래 날짜는 오늘에서 1년 뒤로 잡아 테스트가 날짜에 따라 깨지지 않게 한다. */
    private static String shipDateFor(String code) {
        return switch (code) {
            case "PRODUCT_089" -> "\"" + java.time.LocalDate.now().plusYears(1) + "\"";
            case "PRODUCT_038" -> "\"2020-01-01\"";
            default -> "null";
        };
    }

    @Test
    @DisplayName("이미지 업로드 발급 — 201, 발급한 어드민은 토큰의 memberId, 응답에 업로드 키 · 주소 · 헤더 · 만료")
    void issueImageUpload() {
        given(productAdminUseCase.issueImageUpload(TestTokens.MEMBER_ID, "image/jpeg", 524_288L)).willReturn(
            com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductImageUploadAdminResponse.builder()
                .uploadKey("uploads/1/k.jpg").uploadUrl("https://storage.example.com/b/uploads/1/k.jpg?sig").method("PUT")
                .headers(java.util.Map.of("Content-Type", "image/jpeg")).expiresAt(Instant.parse("2026-11-01T03:05:00Z")).build());

        MvcTestResult result = mvc.post().uri(PATH + "/image-uploads").contentType(MediaType.APPLICATION_JSON)
            .content("{\"contentType\": \"image/jpeg\", \"contentLength\": 524288}")
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.dataBody.uploadKey").isEqualTo("uploads/1/k.jpg");
        assertThat(result).bodyJson().extractingPath("$.dataBody.method").isEqualTo("PUT");
        assertThat(result).bodyJson().extractingPath("$.dataBody.headers.Content-Type").isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("이미지 업로드 발급 — CUSTOMER 403 · 토큰 없음 401, 스토리지가 꺼져 있으면 503 · PRODUCT_036 봉투")
    void issueImageUploadErrors() {
        String body = "{\"contentType\": \"image/png\", \"contentLength\": 10}";
        SecurityEnvelopeAssertions.assertEnvelope(mvc.post().uri(PATH + "/image-uploads").contentType(MediaType.APPLICATION_JSON).content(body)
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(mvc.post().uri(PATH + "/image-uploads").contentType(MediaType.APPLICATION_JSON).content(body)
            .exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        verifyNoInteractions(productAdminUseCase);

        given(productAdminUseCase.issueImageUpload(TestTokens.MEMBER_ID, "image/png", 10L)).willThrow(new com.commercefoundry.domainlayer.product.application.exception.ProductException(
            com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode.IMAGE_UPLOAD_DISABLED));
        MvcTestResult result = mvc.post().uri(PATH + "/image-uploads").contentType(MediaType.APPLICATION_JSON).content(body)
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();
        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_036");
    }

    @Test
    void deleteAuthorizationAndSuccess() {
        SecurityEnvelopeAssertions.assertEnvelope(mvc.delete().uri(PATH + "/7").exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        SecurityEnvelopeAssertions.assertEnvelope(mvc.delete().uri(PATH + "/7").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))
            .exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(productAdminUseCase);
        MvcTestResult result = mvc.delete().uri(PATH + "/7").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        verify(productAdminUseCase).delete(7L, TestTokens.MEMBER_ID);
    }

    @ParameterizedTest
    @CsvSource({"0,PRODUCT_105", "-1,PRODUCT_105", "wrong,PRODUCT_198"})
    void deleteInvalidId(String id, String code) {
        var result = mvc.delete().uri(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(productAdminUseCase);
    }

    @ParameterizedTest
    @CsvSource({"NOT_FOUND_PRODUCT,404,PRODUCT_001", "PRODUCT_MODIFICATION_CONFLICT,409,PRODUCT_010"})
    void deleteErrorEnvelope(String error, int status, String code) {
        org.mockito.Mockito.doThrow(new com.commercefoundry.domainlayer.product.application.exception.ProductException(
            com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode.valueOf(error))).when(productAdminUseCase).delete(7L, TestTokens.MEMBER_ID);
        var result = mvc.delete().uri(PATH + "/7").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
    }

    private MvcTestResult adminPost(String body) {
        return mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();
    }

    private void givenEmptyPage() {
        given(productAdminUseCase.searchProducts(any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
    }

    private MvcTestResult adminGet(String uri) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();
    }

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }
}
