package com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.dto.response.SizeChartGenerationRegistrationResponse;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.dto.response.SizeChartGenerationResultResponse;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationErrorCode;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationException;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.in.SizeChartGenerationInternalUseCase;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(SizeChartGenerationInternalController.class)
@Import({InternalResourceServerConfigurer.class, InternalTokenPropertiesConfig.class, JwtVerificationPropertiesConfig.class})
class SizeChartGenerationInternalControllerTest {
    private static final String PATH = "/internal/v1/size-chart-generation-jobs";
    private static final String ID = "4611686018427387904";
    private static final String BODY = """
        {"draftId":4611686018427387904,"inputHash":"%s","requestedByMemberId":7,"expiresAt":"2099-01-01T00:00:00Z"}
        """.formatted("a".repeat(64));
    @Autowired
    private MockMvcTester mvc;
    @MockitoBean
    private SizeChartGenerationInternalUseCase useCase;

    private String allowed() {
        return TestServiceTokens.bearer("product-service", "operations-service");
    }

    @Test
    void allowedProductCallerRegistersWithEnvelopeAndStringIds() {
        given(useCase.register(any())).willReturn(new SizeChartGenerationRegistrationResponse(ID, ID, "a".repeat(64)));
        var result = mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, allowed()).contentType(MediaType.APPLICATION_JSON).content(BODY).exchange();
        assertThat(result).hasStatus(202);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.draftId").isEqualTo(ID);
        assertThat(result).bodyJson().extractingPath("$.dataBody.jobId").isEqualTo(ID);
        assertThat(result).bodyJson().extractingPath("$.dataBody.inputHash").isEqualTo("a".repeat(64));
    }

    @Test
    void allowedProductCallerReadsResult() {
        given(useCase.result(Long.parseLong(ID))).willReturn(new SizeChartGenerationResultResponse(ID, ID, "a".repeat(64), "SUCCEEDED", null, "{}"));
        var result = mvc.get().uri(PATH + "/" + ID + "/result").header(HttpHeaders.AUTHORIZATION, allowed()).exchange();
        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.dataBody.status").isEqualTo("SUCCEEDED");
        assertThat(result).bodyJson().extractingPath("$.dataBody.resultJson").isEqualTo("{}");
        assertThat(result).bodyJson().extractingPath("$.dataBody.jobId").isEqualTo(ID);
        assertThat(result).bodyJson().extractingPath("$.dataBody.inputHash").isEqualTo("a".repeat(64));
    }

    @ParameterizedTest
    @ValueSource(strings = {"order-service", "auth-service", "payment-service", "delivery-service", "operations-service"})
    void otherServiceCallersDeniedOnBothMethods(String caller) {
        String token = TestServiceTokens.bearer(caller, "operations-service");
        assertThat(mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(BODY).exchange()).hasStatus(403);
        assertThat(mvc.get().uri(PATH + "/1/result").header(HttpHeaders.AUTHORIZATION, token).exchange()).hasStatus(403);
        verifyNoInteractions(useCase);
    }

    @Test
    void anonymousDeniedOnBothMethods() {
        assertThat(mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY).exchange()).hasStatus(401);
        assertThat(mvc.get().uri(PATH + "/1/result").exchange()).hasStatus(401);
        verifyNoInteractions(useCase);
    }

    @Test
    void adminUserTokenCannotCallInternalApi() {
        String token = "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue();
        assertThat(mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(BODY).exchange()).hasStatus(401);
        assertThat(mvc.get().uri(PATH + "/1/result").header(HttpHeaders.AUTHORIZATION, token).exchange()).hasStatus(401);
        verifyNoInteractions(useCase);
    }

    @Test
    void wrongAudienceDenied() {
        String token = TestServiceTokens.bearer("product-service", "order-service");
        assertThat(mvc.get().uri(PATH + "/1/result").header(HttpHeaders.AUTHORIZATION, token).exchange()).hasStatus(401);
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{", "{\"draftId\":0}", "{\"inputHash\":\"private text\"}"})
    void invalidBodyUsesFeatureEnvelope(String body) {
        var result = mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, allowed()).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        verifyNoInteractions(useCase);
    }

    @Test
    void invalidHashUsesFieldCode() {
        var result = mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, allowed()).contentType(MediaType.APPLICATION_JSON)
            .content(BODY.replace("a".repeat(64), "bad")).exchange();
        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SIZE_CHART_GENERATION_111");
        verifyNoInteractions(useCase);
    }

    @Test
    void conflictUsesFeatureEnvelope() {
        given(useCase.register(any())).willThrow(new SizeChartGenerationException(SizeChartGenerationErrorCode.INPUT_CONFLICT));
        var result = mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, allowed()).contentType(MediaType.APPLICATION_JSON).content(BODY).exchange();
        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SIZE_CHART_GENERATION_002");
    }
}
