package com.commercefoundry.domainlayer.sizechart.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.commercefoundry.domainlayer.sizechart.adapter.in.internal.dto.response.SizeChartGenerationInputResponse;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.port.in.SizeChartDraftInternalUseCase;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(SizeChartDraftInternalController.class)
@Import({InternalResourceServerConfigurer.class, InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class, ResourceServerSecurityConfigurer.class})
class SizeChartDraftInternalControllerTest {
    private static final String PATH = "/internal/v1/size-chart-drafts/7/generation-input";
    private static final String IMAGE_PATH = "/internal/v1/size-chart-drafts/7/generation-images/0";
    @Autowired
    private MockMvcTester mvc;
    @MockitoBean
    private SizeChartDraftInternalUseCase useCase;

    @Test
    @WithMockUser(authorities = "SERVICE_operations-service")
    void operationsGetsOnlyImmutableInputEnvelopeWithoutCaching() throws Exception {
        when(useCase.generationInput(7)).thenReturn(new SizeChartGenerationInputResponse("7", "a".repeat(64), "{}", "{}", "TEXT", 0, List.of()));
        var result = mvc.get().uri(PATH).exchange();
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.draftId").isEqualTo("7");
        assertThat(result).bodyJson().extractingPath("$.dataBody.sourceJson").isEqualTo("{}");
        assertThat(result).bodyJson().extractingPath("$.dataBody.templateJson").isEqualTo("{}");
        assertThat(result).bodyJson().extractingPath("$.dataBody.mode").isEqualTo("TEXT");
        assertThat(result).bodyJson().extractingPath("$.dataBody.imageCount").isEqualTo(0);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("requestedBy", "productId", "idempotencyKey");
    }

    @Test
    @WithMockUser(authorities = "SERVICE_operations-service")
    void operationsReadsApprovedImageBytesWithoutEnvelopeOrCaching() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        when(useCase.generationImage(7, 0)).thenReturn(png);
        var result = mvc.get().uri(IMAGE_PATH).exchange();
        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getContentType()).isEqualTo("application/octet-stream");
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(png);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    /** PR2 Feign 계약: 이미지 읽기는 Accept 에 application/json 을 함께 실어야 오류 봉투를 받는다. */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"IMAGE_INPUT_CHANGED,410,SIZE_CHART_DRAFT_016", "IMAGE_INPUT_DISABLED,400,SIZE_CHART_DRAFT_011"})
    @WithMockUser(authorities = "SERVICE_operations-service")
    void failuresAreJsonEnvelopesWhenJsonIsAcceptable(SizeChartDraftTransportErrorCode code, int status, String resultCode) {
        when(useCase.generationImage(7, 0)).thenThrow(new SizeChartDraftTransportException(code));
        var result = mvc.get().uri(IMAGE_PATH).header("Accept", "application/octet-stream, application/json").exchange();
        assertThat(result).hasStatus(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(resultCode);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"IMAGE_INPUT_CHANGED,410", "IMAGE_INPUT_DISABLED,400"})
    @WithMockUser(authorities = "SERVICE_operations-service")
    void failuresKeepStatusAndJsonEnvelopeEvenWhenOnlyOctetStreamIsAccepted(SizeChartDraftTransportErrorCode code, int status) {
        // 핸들러가 Content-Type 을 고정하지 않으면 형식 협상 실패로 처리되지 않은 예외(컨테이너 500)가 된다 — 재시도 불가 410 이 재시도 대상으로 바뀐다.
        when(useCase.generationImage(7, 0)).thenThrow(new SizeChartDraftTransportException(code));
        var result = mvc.get().uri(IMAGE_PATH).header("Accept", "application/octet-stream").exchange();
        assertThat(result).hasStatus(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code.getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SERVICE_product-service", "SERVICE_order-service", "ROLE_ADMIN", "ROLE_USER", "ROLE_OWNER"})
    void everyOtherCallerIsForbiddenBeforeUseCase(String authority) {
        var result = mvc.get().uri(PATH).with(user("caller").authorities(new SimpleGrantedAuthority(authority))).exchange();
        assertThat(result).hasStatus(403);
        assertThat(mvc.get().uri(IMAGE_PATH).with(user("caller").authorities(new SimpleGrantedAuthority(authority))).exchange()).hasStatus(403);
        verifyNoInteractions(useCase);
    }

    @Test
    void anonymousIsUnauthorized() {
        assertThat(mvc.get().uri(PATH).exchange()).hasStatus(401);
        assertThat(mvc.get().uri(IMAGE_PATH).exchange()).hasStatus(401);
        verifyNoInteractions(useCase);
    }

    @Test
    @WithMockUser(authorities = "SERVICE_operations-service")
    void draftFailureHasSafeEnvelopeAndNoStore() {
        when(useCase.generationInput(7)).thenThrow(new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.EXPIRED));
        var result = mvc.get().uri(PATH).exchange();
        assertThat(result).hasStatus(410);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SIZE_CHART_DRAFT_005");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
