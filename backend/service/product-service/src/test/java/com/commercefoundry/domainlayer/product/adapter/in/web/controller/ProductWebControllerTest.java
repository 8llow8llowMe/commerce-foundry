package com.commercefoundry.domainlayer.product.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.CategoryPathItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.CustomOrderFieldItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.CustomOrderItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductSkuItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductThumbnailItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.response.ProductDetailResponse;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.response.ProductListItemResponse;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.model.ProductSortType;
import com.commercefoundry.domainlayer.product.application.port.in.ProductWebUseCase;
import com.commercefoundry.persistence.dto.SliceResponse;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 고객 상품 API 의 HTTP 계약 — 봉투 모양 · 기본 파라미터 · 오류 코드. 슬라이스라 {@code *BeansConfig} 가 빠지므로 보안 구성을 직접 import 한다
 * ({@code bootstrap-conventions.md} 보안 스모크 테스트). 요청은 모두 토큰 없이(익명) 보낸다 — 공개 API 다.
 */
@WebMvcTest(ProductWebController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class ProductWebControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ProductWebUseCase productWebUseCase;

    @Test
    @DisplayName("목록은 익명으로 200 이고 Response<SliceResponse> 봉투다 — 아이디는 문자열, 금액은 {amount, currency}")
    void listEnvelope() {
        ProductListItemResponse item = ProductListItemResponse.builder()
            .productId("4611686018427387904")
            .name("저고리")
            .salePrice(MoneyItem.of(39_000, "KRW"))
            .hasOptionSurcharge(true)
            .saleStatus(CodeNameDescriptionMetadata.of("ON_SALE", "판매중", "구매할 수 있습니다."))
            .customOrder(false)
            .build();
        given(productWebUseCase.searchProducts(null, ProductSortType.NEWEST, 0, 20)).willReturn(new SliceResponse<>(List.of(item), true));

        MvcTestResult result = mvc.get().uri("/api/v1/products").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.hasNext").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].productId").isEqualTo("4611686018427387904");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].salePrice.amount").isEqualTo(39_000);
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].salePrice.currency").isEqualTo("KRW");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].saleStatus.code").isEqualTo("ON_SALE");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].listPrice").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].thumbnail").isNull();
    }

    /** FE 가 타입으로 분기하는 칸 — boolean 은 JSON boolean, 할인율은 숫자 또는 null, 정가는 할인율과 함께 객체 또는 null 이다. */
    @Test
    @DisplayName("목록 JSON 타입 계약 — hasOptionSurcharge · customOrder 는 boolean, discountRate 는 number|null, listPrice 는 할인율과 함께 객체|null")
    void listJsonContract() {
        CodeNameDescriptionMetadata onSale = CodeNameDescriptionMetadata.of("ON_SALE", "판매중", "구매할 수 있습니다.");
        ProductListItemResponse discounted = ProductListItemResponse.builder()
            .productId("1").name("할인").salePrice(MoneyItem.of(39_000, "KRW")).listPrice(MoneyItem.of(45_000, "KRW")).discountRate(13)
            .hasOptionSurcharge(true).saleStatus(onSale).customOrder(true)
            .build();
        ProductListItemResponse regular = ProductListItemResponse.builder()
            .productId("2").name("정가").salePrice(MoneyItem.of(39_000, "KRW")).hasOptionSurcharge(false).saleStatus(onSale).customOrder(false)
            .build();
        given(productWebUseCase.searchProducts(null, ProductSortType.NEWEST, 0, 20)).willReturn(new SliceResponse<>(List.of(discounted, regular), false));

        MvcTestResult result = mvc.get().uri("/api/v1/products").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].productId").asString().isEqualTo("1");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].hasOptionSurcharge").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].customOrder").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].discountRate").asNumber().isEqualTo(13);
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].listPrice.amount").asNumber().isEqualTo(45_000);
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].listPrice.currency").asString().isEqualTo("KRW");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[1].hasOptionSurcharge").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[1].customOrder").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[1].discountRate").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[1].listPrice").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.hasNext").asBoolean().isFalse();
    }

    /** 맞춤 제작 치수(min · max · step)는 BigDecimal 이지만 JSON 숫자로 나간다 — 문자열이면 FE 입력 검증이 깨진다. */
    @Test
    @DisplayName("상세 JSON 타입 계약 — fields[].min · step 은 JSON 숫자, optionValueIds 는 문자열 배열, thumbnail.imageId 는 문자열")
    void detailJsonContract() {
        CodeNameDescriptionMetadata onSale = CodeNameDescriptionMetadata.of("ON_SALE", "판매중", "구매할 수 있습니다.");
        given(productWebUseCase.getProduct(7301L)).willReturn(ProductDetailResponse.builder()
            .productId("4611686018427387904")
            .name("저고리")
            .categoryPath(List.of(CategoryPathItem.builder().categoryId("101").name("상의").build()))
            .thumbnail(ProductThumbnailItem.builder().imageId("88000").url("https://cdn.example.com/t.jpg").width(600).height(null).altText(null).build())
            .images(List.of())
            .salePrice(MoneyItem.of(39_000, "KRW"))
            .saleStatus(onSale)
            .maxQuantityPerOrder(10)
            .optionGroups(List.of())
            .skus(List.of(ProductSkuItem.builder()
                .skuId("9001").optionValueIds(List.of("5011", "5021"))
                .additionalPrice(MoneyItem.of(0, "KRW")).salePrice(MoneyItem.of(39_000, "KRW")).saleStatus(onSale)
                .build()))
            .customOrder(CustomOrderItem.builder().notice("고지").leadTimeDays(14).fields(List.of(CustomOrderFieldItem.builder()
                .fieldKey("chest").label("가슴둘레").inputType("NUMBER").unit("cm").required(true)
                .min(new BigDecimal("60")).max(new BigDecimal("150")).step(new BigDecimal("0.5"))
                .build())).build())
            .build());

        MvcTestResult result = mvc.get().uri("/api/v1/products/7301").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.productId").asString().isEqualTo("4611686018427387904");
        assertThat(result).bodyJson().extractingPath("$.dataBody.categoryPath[0].categoryId").asString().isEqualTo("101");
        assertThat(result).bodyJson().extractingPath("$.dataBody.thumbnail.imageId").asString().isEqualTo("88000");
        assertThat(result).bodyJson().extractingPath("$.dataBody.thumbnail.height").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.skus[0].skuId").asString().isEqualTo("9001");
        assertThat(result).bodyJson().extractingPath("$.dataBody.skus[0].optionValueIds").asArray().containsExactly("5011", "5021");
        assertThat(result).bodyJson().extractingPath("$.dataBody.maxQuantityPerOrder").asNumber().isEqualTo(10);
        assertThat(result).bodyJson().extractingPath("$.dataBody.customOrder.leadTimeDays").asNumber().isEqualTo(14);
        assertThat(result).bodyJson().extractingPath("$.dataBody.customOrder.fields[0].inputType").asString().isEqualTo("NUMBER");
        assertThat(result).bodyJson().extractingPath("$.dataBody.customOrder.fields[0].required").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.dataBody.customOrder.fields[0].min").asNumber()
            .satisfies(min -> assertThat(new BigDecimal(min.toString())).isEqualByComparingTo("60"));
        assertThat(result).bodyJson().extractingPath("$.dataBody.customOrder.fields[0].step").asNumber()
            .satisfies(step -> assertThat(new BigDecimal(step.toString())).isEqualByComparingTo("0.5"));
        assertThat(result).bodyJson().extractingPath("$.dataBody.customOrder.fields[0].helpText").isNull();
    }

    @Test
    @DisplayName("파라미터를 주면 그대로 넘긴다")
    void listPassesParameters() {
        given(productWebUseCase.searchProducts(101L, ProductSortType.PRICE_DESC, 3, 100)).willReturn(new SliceResponse<>(List.of(), false));

        assertThat(mvc.get().uri("/api/v1/products?categoryId=101&sortType=PRICE_DESC&page=3&size=100")).hasStatusOk();
        verify(productWebUseCase).searchProducts(101L, ProductSortType.PRICE_DESC, 3, 100);
    }

    @ParameterizedTest(name = "{0}={1} → {2}")
    @CsvSource({
        "size, 101, PRODUCT_102",
        "size, 0, PRODUCT_102",
        "page, -1, PRODUCT_101",
        "sortType, FOO, PRODUCT_198",
        "page, abc, PRODUCT_198",
        "categoryId, abc, PRODUCT_198"
    })
    @DisplayName("잘못된 목록 파라미터는 400 · 필드별 코드이고 fieldErrors 가 그 파라미터를 짚는다")
    void listInvalidParameter(String name, String value, String code) {
        MvcTestResult result = mvc.get().uri("/api/v1/products").param(name, value).exchange();

        assertBadRequest(result, code, name);
        verifyNoInteractions(productWebUseCase);
    }

    @Test
    @DisplayName("상세는 익명으로 200 이고 Response 봉투다")
    void detailEnvelope() {
        given(productWebUseCase.getProduct(7301L)).willReturn(ProductDetailResponse.builder()
            .productId("7301")
            .name("저고리")
            .categoryPath(List.of())
            .images(List.of())
            .salePrice(MoneyItem.of(39_000, "KRW"))
            .saleStatus(CodeNameDescriptionMetadata.of("STOP_SELLING", "판매 종료", "판매가 종료된 상품입니다."))
            .optionGroups(List.of())
            .skus(List.of())
            .build());

        MvcTestResult result = mvc.get().uri("/api/v1/products/7301").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.productId").isEqualTo("7301");
        assertThat(result).bodyJson().extractingPath("$.dataBody.saleStatus.code").isEqualTo("STOP_SELLING");
        assertThat(result).bodyJson().extractingPath("$.dataBody.customOrder").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.skus").asArray().isEmpty();
    }

    @Test
    @DisplayName("없거나 숨긴 상품은 404 · PRODUCT_001 이고 fieldErrors · dataBody 는 null 이다")
    void detailNotFound() {
        given(productWebUseCase.getProduct(7301L)).willThrow(new ProductException(ProductErrorCode.NOT_FOUND_PRODUCT));

        MvcTestResult result = mvc.get().uri("/api/v1/products/7301").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_001");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("존재하지 않는 상품입니다.");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    @Test
    @DisplayName("상품 아이디가 숫자가 아니면 400 · PRODUCT_198 이다")
    void detailIdNotNumber() {
        assertBadRequest(mvc.get().uri("/api/v1/products/abc").exchange(), "PRODUCT_198", "productId");
        verifyNoInteractions(productWebUseCase);
    }

    private static void assertBadRequest(MvcTestResult result, String code, String field) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").asString().isNotBlank();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }
}
