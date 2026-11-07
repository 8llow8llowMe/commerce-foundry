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
    @Autowired
    private MockMvcTester mvc;
    @MockitoBean
    private SizeChartDraftInternalUseCase useCase;

    @Test
    @WithMockUser(authorities = "SERVICE_operations-service")
    void operationsGetsOnlyImmutableInputEnvelopeWithoutCaching() throws Exception {
        when(useCase.generationInput(7)).thenReturn(new SizeChartGenerationInputResponse("7", "a".repeat(64), "{}", "{}"));
        var result = mvc.get().uri(PATH).exchange();
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.draftId").isEqualTo("7");
        assertThat(result).bodyJson().extractingPath("$.dataBody.sourceJson").isEqualTo("{}");
        assertThat(result).bodyJson().extractingPath("$.dataBody.templateJson").isEqualTo("{}");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("requestedBy", "productId", "idempotencyKey");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SERVICE_product-service", "SERVICE_order-service", "ROLE_ADMIN", "ROLE_USER", "ROLE_OWNER"})
    void everyOtherCallerIsForbiddenBeforeUseCase(String authority) {
        var result = mvc.get().uri(PATH).with(user("caller").authorities(new SimpleGrantedAuthority(authority))).exchange();
        assertThat(result).hasStatus(403);
        verifyNoInteractions(useCase);
    }

    @Test
    void anonymousIsUnauthorized() {
        assertThat(mvc.get().uri(PATH).exchange()).hasStatus(401);
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
