package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductDetailAdminResponse;
import com.commercefoundry.domainlayer.product.application.command.ProductCustomOrderUpdateCommand;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderInputType;
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

/** {@code PUT /api/v1/admin/products/{productId}/custom-order} 진입 검증 (#9 PR-B). */
@WebMvcTest(ProductAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class ProductCustomOrderControllerTest {

    static final String PATH = "/api/v1/admin/products/7/custom-order";
    static final String FIELD = "{\"fieldKey\":\"chest\",\"label\":\"  가슴둘레  \",\"inputType\":\"NUMBER\",\"unit\":\" \",\"required\":true,\"minValue\":60}";

    @Autowired MockMvcTester mvc;
    @MockitoBean ProductAdminUseCase products;

    @Test
    @DisplayName("ADMIN 만 쓰고, 행위자는 토큰에서 오며, 문구는 앞뒤 공백을 빼고 빈 문구는 null 이다")
    void requiresAdminAndNormalizes() {
        String body = "{\"version\":3,\"enabled\":true,\"notice\":\"  2주 걸립니다\\n주문 뒤 연락드립니다 \",\"leadTimeDays\":14,\"fields\":[" + FIELD + "]}";
        SecurityEnvelopeAssertions.assertEnvelope(mvc.put().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        SecurityEnvelopeAssertions.assertEnvelope(mvc.put().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .header(HttpHeaders.AUTHORIZATION, token(MemberRole.CUSTOMER)).exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(products);
        when(products.updateCustomOrder(any())).thenReturn(ProductDetailAdminResponse.builder().productId("7").version(4L).build());

        var result = mvc.put().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)).exchange();

        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.dataBody.productId").isEqualTo("7");
        var captured = ArgumentCaptor.forClass(ProductCustomOrderUpdateCommand.class);
        verify(products).updateCustomOrder(captured.capture());
        var command = captured.getValue();
        assertThat(command.productId()).isEqualTo(7);
        assertThat(command.version()).isEqualTo(3);
        assertThat(command.actorMemberId()).isEqualTo(TestTokens.MEMBER_ID);
        assertThat(command.settings().notice()).isEqualTo("2주 걸립니다\n주문 뒤 연락드립니다");
        var field = command.settings().fields().getFirst();
        assertThat(field.label()).isEqualTo("가슴둘레");
        assertThat(field.unit()).isNull();
        assertThat(field.inputType()).isEqualTo(CustomOrderInputType.NUMBER);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    @DisplayName("필드 검증은 서비스에 닿기 전에 400 과 정확한 코드다")
    void rejectsInvalidFields(String body, String code) {
        var result = mvc.put().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
            .header(HttpHeaders.AUTHORIZATION, token(MemberRole.ADMIN)).exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(products);
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
            args("{\"enabled\":true,\"fields\":[]}", "PRODUCT_EDIT_101"),
            args("{\"version\":0,\"fields\":[]}", "PRODUCT_EDIT_104"),
            args("{\"version\":0,\"enabled\":true,\"notice\":\"" + "a".repeat(501) + "\",\"fields\":[]}", "PRODUCT_EDIT_105"),
            // 긴 문구에서도 정규식이 스택을 넘기지 않고 400 이다(500 이 아니다)
            args("{\"version\":0,\"enabled\":true,\"notice\":\"" + "가\\n".repeat(20_000) + "\",\"fields\":[]}", "PRODUCT_EDIT_105"),
            args("{\"version\":0,\"enabled\":true,\"notice\":\"bad\\ttab\",\"fields\":[]}", "PRODUCT_EDIT_105"),
            args("{\"version\":0,\"enabled\":true,\"leadTimeDays\":0,\"fields\":[]}", "PRODUCT_EDIT_106"),
            args("{\"version\":0,\"enabled\":true,\"leadTimeDays\":366,\"fields\":[]}", "PRODUCT_EDIT_106"),
            args("{\"version\":0,\"enabled\":true}", "PRODUCT_EDIT_107"),
            args("{\"version\":0,\"enabled\":true,\"fields\":[" + String.join(",", java.util.Collections.nCopies(21, FIELD)) + "]}", "PRODUCT_EDIT_107"),
            args("{\"version\":0,\"enabled\":true,\"fields\":[" + FIELD.replace("chest", "Chest") + "]}", "PRODUCT_EDIT_108"),
            args("{\"version\":0,\"enabled\":true,\"fields\":[" + FIELD.replace("chest", "1chest") + "]}", "PRODUCT_EDIT_108"),
            args("{\"version\":0,\"enabled\":true,\"fields\":[" + FIELD.replace("\"chest\"", "\"" + "c".repeat(31) + "\"") + "]}", "PRODUCT_EDIT_108"),
            args("{\"version\":0,\"enabled\":true,\"fields\":[" + FIELD.replace("  가슴둘레  ", " ") + "]}", "PRODUCT_EDIT_109"),
            args("{\"version\":0,\"enabled\":true,\"fields\":[" + FIELD.replace("  가슴둘레  ", "a".repeat(51)) + "]}", "PRODUCT_EDIT_109"),
            args("{\"version\":0,\"enabled\":true,\"fields\":[" + FIELD.replace(",\"inputType\":\"NUMBER\"", "") + "]}", "PRODUCT_EDIT_110"),
            args("{\"version\":0,\"enabled\":true,\"fields\":[" + FIELD.replace("\"unit\":\" \"", "\"unit\":\"" + "u".repeat(11) + "\"") + "]}",
                "PRODUCT_EDIT_111"),
            args("{\"version\":0,\"enabled\":true,\"fields\":[" + FIELD.replace(",\"required\":true", "") + "]}", "PRODUCT_EDIT_112"));
    }

    private static Arguments args(String body, String code) {
        return Arguments.of(body, code);
    }

    private static String token(MemberRole role) {
        return "Bearer " + TestTokens.issuer(java.time.Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }
}
