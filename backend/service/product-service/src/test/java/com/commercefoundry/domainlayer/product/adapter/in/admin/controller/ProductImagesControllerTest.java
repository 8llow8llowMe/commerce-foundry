package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.domainlayer.product.application.command.ProductImagesUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.*;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductDetailAdminResponse;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(ProductAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class ProductImagesControllerTest {
    static final String PATH = "/api/v1/admin/products/7/images";
    @Autowired MockMvcTester mvc;
    @MockitoBean ProductAdminUseCase products;

    @Test
    void requiresAdminAndUsesActorFromTokenAndIgnoresUntrustedObjectAttributes() {
        String body = """
            {"version":0,"images":[{"imageId":"8","imageType":"GALLERY","altText":"  edited  ",
            "url":"https://attacker.invalid/image","storageKey":"uploads/attacker","width":999}]}
            """;
        SecurityEnvelopeAssertions.assertEnvelope(mvc.put().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        SecurityEnvelopeAssertions.assertEnvelope(mvc.put().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .header(HttpHeaders.AUTHORIZATION, token(MemberRole.CUSTOMER)).exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(products);
        when(products.updateImages(any())).thenReturn(ProductDetailAdminResponse.builder().productId("7").version(1L).build());
        var result = mvc.put().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)).exchange();
        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.dataBody.productId").isEqualTo("7");
        var captured = ArgumentCaptor.forClass(ProductImagesUpdateCommand.class);
        verify(products).updateImages(captured.capture());
        assertThat(captured.getValue().actorMemberId()).isEqualTo(TestTokens.MEMBER_ID);
        assertThat(captured.getValue().images().getFirst().altText()).isEqualTo("edited");
        assertThat(captured.getValue().images().getFirst().imageId()).isEqualTo(8L);
        assertThat(captured.getValue().images().getFirst().uploadKey()).isNull();
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void rejectsInvalidFieldsWithExactCode(String body, String code) {
        var result = mvc.put().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)).exchange();
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(products);
    }

    @Test
    void rawUrlWithoutReferenceIsRejectedAsConfigurationByApplication() {
        when(products.updateImages(any())).thenThrow(new ProductException(ProductErrorCode.IMAGE_CONFIGURATION_INVALID));
        var result = mvc.put().uri(PATH).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":0,\"images\":[{\"url\":\"https://attacker.invalid\",\"storageKey\":\"raw\",\"imageType\":\"GALLERY\"}]}")
            .header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)).exchange();
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_018");
        var captured = ArgumentCaptor.forClass(ProductImagesUpdateCommand.class);
        verify(products).updateImages(captured.capture());
        assertThat(captured.getValue().images().getFirst().imageId()).isNull();
        assertThat(captured.getValue().images().getFirst().uploadKey()).isNull();
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidBodies() {
        return java.util.stream.Stream.of(
            args("{\"images\":[]}", "PRODUCT_EDIT_101"), args("{\"version\":-1,\"images\":[]}", "PRODUCT_EDIT_101"),
            args("{\"version\":0}", "PRODUCT_019"), args("{\"version\":0,\"images\":[null]}", "PRODUCT_019"),
            args("{\"version\":0,\"images\":[{\"imageId\":0,\"imageType\":\"GALLERY\"}]}", "PRODUCT_019"),
            args("{\"version\":0,\"images\":[{\"imageId\":8}]}", "PRODUCT_019"),
            args("{\"version\":0,\"images\":[{\"imageId\":8,\"imageType\":\"OTHER\"}]}", "PRODUCT_100"),
            args("{\"version\":0,\"images\":[" + String.join(",", java.util.Collections.nCopies(9,
                "{\"imageId\":8,\"imageType\":\"GALLERY\"}")) + "]}", "PRODUCT_019"),
            args("{\"version\":0,\"images\":[{\"imageId\":8,\"imageType\":\"GALLERY\",\"altText\":\"bad\\u0001text\"}]}", "PRODUCT_019"),
            args("{\"version\":0,\"images\":[{\"imageId\":8,\"imageType\":\"GALLERY\",\"altText\":\"" + "a".repeat(201) + "\"}]}", "PRODUCT_019"));
    }
    private static org.junit.jupiter.params.provider.Arguments args(String body, String code) { return org.junit.jupiter.params.provider.Arguments.of(body, code); }
    private static String token(MemberRole role) { return "Bearer " + TestTokens.issuer(java.time.Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue(); }
}
