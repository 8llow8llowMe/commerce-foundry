package com.commercefoundry.domainlayer.productdraft.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.commercefoundry.domainlayer.productdraft.adapter.in.internal.dto.response.ProductDraftGenerationInputResponse;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.in.ProductDraftInternalUseCase;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** operations 전용 인가 · 캐시 금지 · 바이트 응답 헤더 · 오류 JSON 봉투 고정 (선례: {@code SizeChartDraftInternalControllerTest}). */
@WebMvcTest(ProductDraftInternalController.class)
@Import({InternalResourceServerConfigurer.class, InternalTokenPropertiesConfig.class, JwtVerificationPropertiesConfig.class, ResourceServerSecurityConfigurer.class})
class ProductDraftInternalControllerTest {

    private static final String INPUT_PATH = "/internal/v1/product-drafts/7/generation-input";
    private static final String IMAGE_PATH = "/internal/v1/product-drafts/7/generation-images/0";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ProductDraftInternalUseCase useCase;

    @Test
    @WithMockUser(authorities = "SERVICE_operations-service")
    @DisplayName("operations 는 봉투에 담긴 생성 입력을 받고 캐시하지 않는다")
    void generationInput() {
        when(useCase.generationInput(7)).thenReturn(new ProductDraftGenerationInputResponse("7", "REQUESTED", Instant.parse("2026-11-18T03:00:00Z"),
            new ProductDraftGenerationInputResponse.Facts("린넨 저고리", List.of(), List.of(), null, null, List.of(), null, List.of(), List.of()), 1,
            List.of(new ProductDraftGenerationInputResponse.Image(0, "THUMBNAIL", "image/png", 12, null, null))));

        var result = mvc.get().uri(INPUT_PATH).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.draftId").isEqualTo("7");
        assertThat(result).bodyJson().extractingPath("$.dataBody.imageCount").isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.dataBody.images[0].contentType").isEqualTo("image/png");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    @WithMockUser(authorities = "SERVICE_operations-service")
    @DisplayName("이미지 바이트는 봉투 없이 octet-stream · no-store · nosniff 로 나간다")
    void generationImage() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        when(useCase.generationImage(7, 0)).thenReturn(png);

        var result = mvc.get().uri(IMAGE_PATH).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getContentType()).isEqualTo("application/octet-stream");
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(png);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @ParameterizedTest
    @CsvSource({
        "GENERATION_IMAGE_CHANGED,410", "GENERATION_IMAGE_NOT_FOUND,404", "GENERATION_DISABLED,400", "GENERATION_INPUT_UNAVAILABLE,409",
        "IMAGE_STORAGE_UNAVAILABLE,503"
    })
    @WithMockUser(authorities = "SERVICE_operations-service")
    @DisplayName("이미지 읽기 오류는 octet-stream 만 받아도 상태 · 코드를 지킨 JSON 봉투 · no-store 다")
    void imageFailuresAreJsonEnvelopes(ProductDraftErrorCode code, int status) {
        when(useCase.generationImage(7, 0)).thenThrow(new ProductDraftException(code));

        var result = mvc.get().uri(IMAGE_PATH).header("Accept", "application/octet-stream").exchange();

        assertThat(result).hasStatus(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code.getCode());
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @ParameterizedTest
    @CsvSource({"NOT_FOUND_DRAFT,404", "DRAFT_EXPIRED,409", "GENERATION_DRAFT_REJECTED,409", "GENERATION_STATUS_INVALID,409"})
    @WithMockUser(authorities = "SERVICE_operations-service")
    @DisplayName("생성 입력의 없음 · 만료 · 거절 · 상태 사유는 구분된 코드의 봉투다")
    void inputFailures(ProductDraftErrorCode code, int status) {
        when(useCase.generationInput(7)).thenThrow(new ProductDraftException(code));

        var result = mvc.get().uri(INPUT_PATH).exchange();

        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code.getCode());
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SERVICE_product-service", "SERVICE_order-service", "SERVICE_payment-service", "ROLE_ADMIN", "ROLE_OWNER", "ROLE_CUSTOMER"})
    @DisplayName("operations 밖의 서비스 토큰 · 사용자 역할은 유스케이스 전에 403 이다")
    void otherCallersAreForbidden(String authority) {
        var caller = user("caller").authorities(new SimpleGrantedAuthority(authority));
        assertThat(mvc.get().uri(INPUT_PATH).with(caller).exchange()).hasStatus(403);
        assertThat(mvc.get().uri(IMAGE_PATH).with(caller).exchange()).hasStatus(403);
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("토큰이 없으면 401 이다")
    void anonymousIsUnauthorized() {
        assertThat(mvc.get().uri(INPUT_PATH).exchange()).hasStatus(401);
        assertThat(mvc.get().uri(IMAGE_PATH).exchange()).hasStatus(401);
        verifyNoInteractions(useCase);
    }
}
