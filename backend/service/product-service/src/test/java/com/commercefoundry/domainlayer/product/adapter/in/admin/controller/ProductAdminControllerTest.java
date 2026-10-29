package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductListAdminItem;
import com.commercefoundry.domainlayer.product.application.command.ProductAdminSearchCommand;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSortType;
import com.commercefoundry.domainlayer.product.application.model.ProductAvailabilityFilter;
import com.commercefoundry.domainlayer.product.application.model.ProductSaleStatusFilter;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
