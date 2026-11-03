package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductDetailAdminResponse;
import com.commercefoundry.domainlayer.product.application.command.SkuAddCommand;
import com.commercefoundry.domainlayer.product.application.port.in.SkuAdminUseCase;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
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

/** {@code POST /api/v1/admin/products/{productId}/skus} 진입 검증 (#9 PR-C1). */
@WebMvcTest(SkuAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class SkuAddControllerTest {

    static final String PATH = "/api/v1/admin/products/7/skus";
    static final String SKU = "{\"optionValues\":[\" 블랙 \",\"L\"],\"additionalPrice\":{\"amount\":0,\"currency\":\"KRW\"},\"saleStatus\":\"ON_SALE\","
        + "\"hsCode\":\"6211.42\",\"initialStock\":{\"stockManaged\":true,\"quantity\":5}}";

    @Autowired MockMvcTester mvc;
    @MockitoBean SkuAdminUseCase skus;

    @Test
    @DisplayName("ADMIN 만 쓰고 201 이다. 행위자는 토큰에서 오고 이름은 앞뒤 공백을 뺀다")
    void requiresAdminAndCreates() {
        String body = "{\"version\":2,\"newOptionValues\":[{\"optionGroupId\":\"11\",\"name\":\" L \"}],\"skus\":[" + SKU + "]}";
        SecurityEnvelopeAssertions.assertEnvelope(mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        SecurityEnvelopeAssertions.assertEnvelope(mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .header(HttpHeaders.AUTHORIZATION, token(MemberRole.CUSTOMER)).exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(skus);
        when(skus.add(any())).thenReturn(ProductDetailAdminResponse.builder().productId("7").version(3L).build());

        var result = mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)).exchange();

        assertThat(result).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.dataBody.productId").isEqualTo("7");
        var captured = ArgumentCaptor.forClass(SkuAddCommand.class);
        verify(skus).add(captured.capture());
        var command = captured.getValue();
        assertThat(command.actorMemberId()).isEqualTo(TestTokens.MEMBER_ID);
        assertThat(command.version()).isEqualTo(2);
        assertThat(command.newOptionValues()).containsExactly(new SkuAddCommand.NewOptionValue(11, "L"));
        assertThat(command.skus().getFirst().optionValues()).containsExactly("블랙", "L");
        assertThat(command.skus().getFirst().hsCode().value()).isEqualTo("621142");
        assertThat(command.skus().getFirst().initialStock()).isEqualTo(new SkuAddCommand.InitialStock(true, 5));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    @DisplayName("필드 검증은 서비스에 닿기 전에 400 과 정확한 코드다")
    void rejectsInvalidFields(String body, String code) {
        var result = mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)).exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(skus);
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
            args("{\"skus\":[" + SKU + "]}", "SKU_ADMIN_101"),
            args("{\"version\":0,\"skus\":[]}", "SKU_ADMIN_113"),
            args("{\"version\":0}", "SKU_ADMIN_113"),
            args("{\"version\":0,\"newOptionValues\":[{\"name\":\"L\"}],\"skus\":[" + SKU + "]}", "SKU_ADMIN_103"),
            args("{\"version\":0,\"newOptionValues\":[{\"optionGroupId\":1,\"name\":\" \"}],\"skus\":[" + SKU + "]}", "SKU_ADMIN_112"),
            args("{\"version\":0,\"newOptionValues\":[{\"optionGroupId\":1,\"name\":\"bad\\ttab\"}],\"skus\":[" + SKU + "]}", "SKU_ADMIN_112"),
            args("{\"version\":0,\"skus\":[" + SKU.replace("[\" 블랙 \",\"L\"]", "[]") + "]}", "SKU_ADMIN_114"),
            args("{\"version\":0,\"skus\":[" + SKU.replace(",\"saleStatus\":\"ON_SALE\"", "") + "]}", "SKU_ADMIN_105"),
            args("{\"version\":0,\"skus\":[" + SKU.replace("\"amount\":0", "\"amount\":-1") + "]}", "SKU_ADMIN_108"),
            args("{\"version\":0,\"skus\":[" + SKU.replace("6211.42", "12") + "]}", "SKU_ADMIN_107"),
            args("{\"version\":0,\"skus\":[" + SKU.replace("\"quantity\":5", "\"quantity\":-1") + "]}", "SKU_ADMIN_115"),
            args("{\"version\":0,\"skus\":[" + SKU.replace("\"stockManaged\":true,", "") + "]}", "SKU_ADMIN_115"));
    }

    private static Arguments args(String body, String code) {
        return Arguments.of(body, code);
    }

    private static String token(MemberRole role) {
        return "Bearer " + TestTokens.issuer(java.time.Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }
}
