package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductSkuAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductDetailAdminResponse;
import com.commercefoundry.domainlayer.product.application.command.SkuBulkUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminException;
import com.commercefoundry.domainlayer.product.application.port.in.SkuAdminUseCase;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(SkuAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class SkuAdminControllerTest {
    static final String PATH = "/api/v1/admin/products/7/skus";
    static final String ITEM = "{\"skuId\":\"4611686018427387904\",\"additionalPrice\":{\"amount\":0,\"currency\":\"KRW\"},"
        + "\"saleStatus\":\"ON_SALE\",\"hsCode\":\"6211.42-1000\"}";
    static final String BODY = "{\"version\":0,\"skus\":[" + ITEM + "]}";
    @Autowired MockMvcTester mvc;
    @MockitoBean SkuAdminUseCase useCase;

    @Test
    void anonymousAndCustomerCannotReachUseCase() {
        SecurityEnvelopeAssertions.assertEnvelope(mvc.put().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
            .exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        SecurityEnvelopeAssertions.assertEnvelope(mvc.put().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(useCase);
    }

    @Test
    void adminActorComesFromJwtAndIdsStayStringsInDetailWithEnumMetadata() {
        given(useCase.replace(any())).willReturn(ProductDetailAdminResponse.builder().productId("7").version(1L)
            .skus(List.of(ProductSkuAdminItem.builder().skuId("4611686018427387904").optionValueIds(List.of("99"))
                .additionalPrice(MoneyItem.of(0, "KRW")).saleStatus(SaleStatus.ON_SALE.toMetadata()).hsCode("6211421000").build())).build());
        var result = mvc.put().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();
        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.dataBody.productId").isEqualTo("7");
        assertThat(result).bodyJson().extractingPath("$.dataBody.skus[0].skuId").isEqualTo("4611686018427387904");
        assertThat(result).bodyJson().extractingPath("$.dataBody.skus[0].optionValueIds[0]").isEqualTo("99");
        assertThat(result).bodyJson().extractingPath("$.dataBody.skus[0].saleStatus.code").isEqualTo("ON_SALE");
        var capture = ArgumentCaptor.forClass(SkuBulkUpdateCommand.class);
        verify(useCase).replace(capture.capture());
        assertThat(capture.getValue().actorMemberId()).isEqualTo(TestTokens.MEMBER_ID);
        assertThat(capture.getValue().productId()).isEqualTo(7);
        assertThat(capture.getValue().skus().getFirst().skuId()).isEqualTo(4_611_686_018_427_387_904L);
        assertThat(capture.getValue().skus().getFirst().hsCode().value()).isEqualTo("6211421000");
        assertThat(capture.getValue().skus().getFirst().weightGrams()).isNull();
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void fieldValidationRejectsBeforeUseCase(String body, String code) {
        var result = mvc.put().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(useCase);
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
            Arguments.of(BODY.replace("\"version\":0,", ""), "SKU_ADMIN_101"),
            Arguments.of(BODY.replace("\"version\":0", "\"version\":-1"), "SKU_ADMIN_101"),
            Arguments.of("{\"version\":0}", "SKU_ADMIN_102"),
            Arguments.of("{\"version\":0,\"skus\":[]}", "SKU_ADMIN_102"),
            Arguments.of("{\"version\":0,\"skus\":[null]}", "SKU_ADMIN_102"),
            Arguments.of("{\"version\":0,\"skus\":[" + String.join(",", java.util.Collections.nCopies(101, ITEM)) + "]}", "SKU_ADMIN_102"),
            Arguments.of(BODY.replace("\"4611686018427387904\"", "0"), "SKU_ADMIN_103"),
            Arguments.of(BODY.replace("\"4611686018427387904\"", "null"), "SKU_ADMIN_103"),
            Arguments.of(BODY.replace("{\"amount\":0,\"currency\":\"KRW\"}", "null"), "SKU_ADMIN_104"),
            Arguments.of(BODY.replace("\"ON_SALE\"", "null"), "SKU_ADMIN_105"),
            Arguments.of(BODY.replace("\"hsCode\":", "\"weightGrams\":0,\"hsCode\":"), "SKU_ADMIN_106"),
            Arguments.of(BODY.replace("6211.42-1000", "12345"), "SKU_ADMIN_107"),
            Arguments.of(BODY.replace("6211.42-1000", "12345678901"), "SKU_ADMIN_107"),
            Arguments.of(BODY.replace("6211.42-1000", "１２３４５６"), "SKU_ADMIN_107"),
            Arguments.of(BODY.replace("\"amount\":0", "\"amount\":null"), "SKU_ADMIN_108"),
            Arguments.of(BODY.replace("\"amount\":0", "\"amount\":-1"), "SKU_ADMIN_108"),
            Arguments.of(BODY.replace("\"amount\":0", "\"amount\":100000001"), "SKU_ADMIN_108"),
            Arguments.of(BODY.replace("\"currency\":\"KRW\"", "\"currency\":null"), "SKU_ADMIN_109"),
            Arguments.of(BODY.replace("\"version\":0", "\"version\":1.5"), "PRODUCT_100"),
            Arguments.of(BODY.replace("\"amount\":0", "\"amount\":1.5"), "PRODUCT_100"),
            Arguments.of(BODY.replace("\"4611686018427387904\"", "1.5"), "PRODUCT_100"),
            Arguments.of(BODY.replace("\"hsCode\":", "\"weightGrams\":1.5,\"hsCode\":"), "PRODUCT_100"),
            Arguments.of(BODY.replace("\"version\":0", "\"version\":9223372036854775808"), "PRODUCT_100"),
            Arguments.of(BODY.replace("\"amount\":0", "\"amount\":9223372036854775808"), "PRODUCT_100"),
            Arguments.of(BODY.replace("\"4611686018427387904\"", "9223372036854775808"), "PRODUCT_100"),
            Arguments.of(BODY.replace("ON_SALE", "SOLD_OUT"), "PRODUCT_100")
        );
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(SkuAdminErrorCode.class)
    void businessErrorsKeepOwnStatusAndEnvelope(SkuAdminErrorCode code) {
        given(useCase.replace(any())).willThrow(new SkuAdminException(code, "가격 규칙"));
        var result = mvc.put().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();
        assertThat(result).hasStatus(code.getHttpStatus()).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code.getCode());
    }

    private static String bearer(MemberRole role) { return "Bearer " + TestTokens.issuer(java.time.Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue(); }
}
