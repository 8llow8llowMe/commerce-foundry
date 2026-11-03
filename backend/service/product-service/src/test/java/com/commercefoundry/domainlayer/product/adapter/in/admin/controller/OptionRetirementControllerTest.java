package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductDetailAdminResponse;
import com.commercefoundry.domainlayer.product.application.command.OptionRetirementCommand;
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

/** {@code POST /api/v1/admin/products/{productId}/option-retirements} 진입 검증 (#9 PR-C2). */
@WebMvcTest(SkuAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class OptionRetirementControllerTest {

    static final String PATH = "/api/v1/admin/products/7/option-retirements";

    @Autowired MockMvcTester mvc;
    @MockitoBean SkuAdminUseCase skus;

    @Test
    @DisplayName("ADMIN 만 쓰고, 행위자는 토큰에서 오며 같은 아이디는 한 번만 넘긴다")
    void requiresAdmin() {
        String body = "{\"version\":1,\"optionGroupIds\":[\"11\"],\"optionValueIds\":[\"21\",21]}";
        SecurityEnvelopeAssertions.assertEnvelope(mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        SecurityEnvelopeAssertions.assertEnvelope(mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .header(HttpHeaders.AUTHORIZATION, token(MemberRole.CUSTOMER)).exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(skus);
        when(skus.retireOptions(any())).thenReturn(ProductDetailAdminResponse.builder().productId("7").version(2L).build());

        var result = mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)).exchange();

        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.dataBody.productId").isEqualTo("7");
        var captured = ArgumentCaptor.forClass(OptionRetirementCommand.class);
        verify(skus).retireOptions(captured.capture());
        assertThat(captured.getValue().actorMemberId()).isEqualTo(TestTokens.MEMBER_ID);
        assertThat(captured.getValue().optionGroupIds()).containsExactly(11L);
        assertThat(captured.getValue().optionValueIds()).containsExactly(21L);
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
            Arguments.of("{\"optionValueIds\":[1]}", "SKU_ADMIN_101"),
            Arguments.of("{\"version\":0}", "SKU_ADMIN_116"),
            Arguments.of("{\"version\":0,\"optionGroupIds\":[],\"optionValueIds\":[]}", "SKU_ADMIN_116"),
            Arguments.of("{\"version\":0,\"optionValueIds\":[0]}", "SKU_ADMIN_103"),
            Arguments.of("{\"version\":0,\"optionValueIds\":[" + "1,".repeat(50) + "1]}", "SKU_ADMIN_116"));
    }

    private static String token(MemberRole role) {
        return "Bearer " + TestTokens.issuer(java.time.Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }
}
