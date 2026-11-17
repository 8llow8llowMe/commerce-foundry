package com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.dto.response.ProductDraftGenerationRegistrationResponse;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.dto.response.ProductDraftGenerationResultResponse;
import com.commercefoundry.domainlayer.productdraftgeneration.application.command.ProductDraftGenerationRegisterCommand;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationErrorCode;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationException;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.in.ProductDraftGenerationInternalUseCase;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(ProductDraftGenerationInternalController.class)
@Import({InternalResourceServerConfigurer.class, InternalTokenPropertiesConfig.class, JwtVerificationPropertiesConfig.class})
class ProductDraftGenerationInternalControllerTest {
    private static final String PATH = "/internal/v1/product-draft-generation-jobs";
    private static final String ID = "4611686018427387904";
    private static final String HASH = "a".repeat(64);
    private static final String BODY = """
        {"draftId":4611686018427387904,"generationNo":2,"inputHash":"%s","actorMemberId":7,"expiresAt":"2099-01-01T00:00:00Z"}
        """.formatted(HASH);
    @Autowired
    private MockMvcTester mvc;
    @MockitoBean
    private ProductDraftGenerationInternalUseCase useCase;

    private String allowed() {
        return TestServiceTokens.bearer("product-service", "operations-service");
    }

    @Test
    void allowedProductCallerRegistersWithEnvelopeAndStringIds() {
        given(useCase.register(any())).willReturn(new ProductDraftGenerationRegistrationResponse(ID, 2, ID, HASH));
        var result = mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, allowed()).contentType(MediaType.APPLICATION_JSON).content(BODY).exchange();
        assertThat(result).hasStatus(202);
        assertThat(result).hasHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.draftId").isEqualTo(ID);
        assertThat(result).bodyJson().extractingPath("$.dataBody.generationNo").isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.dataBody.jobId").isEqualTo(ID);
        assertThat(result).bodyJson().extractingPath("$.dataBody.inputHash").isEqualTo(HASH);
        verify(useCase).register(new ProductDraftGenerationRegisterCommand(Long.parseLong(ID), 2, HASH, 7, Instant.parse("2099-01-01T00:00:00Z")));
    }

    @Test
    void allowedProductCallerReadsResult() {
        given(useCase.result(Long.parseLong(ID))).willReturn(new ProductDraftGenerationResultResponse(ID, 2, ID, HASH, "SUCCEEDED", null, "{}"));
        var result = mvc.get().uri(PATH + "/" + ID + "/result").header(HttpHeaders.AUTHORIZATION, allowed()).exchange();
        assertThat(result).hasStatus(200);
        assertThat(result).hasHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        assertThat(result).bodyJson().extractingPath("$.dataBody.status").isEqualTo("SUCCEEDED");
        assertThat(result).bodyJson().extractingPath("$.dataBody.resultJson").isEqualTo("{}");
        assertThat(result).bodyJson().extractingPath("$.dataBody.jobId").isEqualTo(ID);
        assertThat(result).bodyJson().extractingPath("$.dataBody.generationNo").isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"order-service", "auth-service", "payment-service", "delivery-service", "operations-service", "support-service"})
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
    @ValueSource(strings = {"{}", "{", "{\"draftId\":0}", "{\"inputHash\":\"private text\"}", "{\"generationNo\":\"two\"}"})
    void invalidBodyUsesFeatureEnvelope(String body) {
        var result = mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, allowed()).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        assertThat(result).hasStatus(400);
        assertThat(result).hasHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @CsvSource({
        "'\"generationNo\":2', '\"generationNo\":4', PRODUCT_DRAFT_GENERATION_111",
        "'\"generationNo\":2', '\"generationNo\":0', PRODUCT_DRAFT_GENERATION_111",
        "'\"generationNo\":2,', '', PRODUCT_DRAFT_GENERATION_111",
        "'\"inputHash\":\"a', '\"inputHash\":\"A', PRODUCT_DRAFT_GENERATION_121",
        "'\"inputHash\":\"a', '\"inputHash\":\"', PRODUCT_DRAFT_GENERATION_121",
        "'\"actorMemberId\":7', '\"actorMemberId\":0', PRODUCT_DRAFT_GENERATION_131",
        "'\"draftId\":4611686018427387904', '\"draftId\":-1', PRODUCT_DRAFT_GENERATION_101",
        "',\"expiresAt\":\"2099-01-01T00:00:00Z\"', '', PRODUCT_DRAFT_GENERATION_141"
    })
    void invalidFieldUsesFieldCode(String from, String to, String code) {
        var result = mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, allowed()).contentType(MediaType.APPLICATION_JSON)
            .content(BODY.replace(from, to)).exchange();
        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(useCase);
    }

    @Test
    void nonPositiveJobIdUsesFieldCode() {
        var result = mvc.get().uri(PATH + "/0/result").header(HttpHeaders.AUTHORIZATION, allowed()).exchange();
        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_DRAFT_GENERATION_151");
        verifyNoInteractions(useCase);
    }

    @Test
    void conflictUsesFeatureEnvelope() {
        given(useCase.register(any())).willThrow(new ProductDraftGenerationException(ProductDraftGenerationErrorCode.INPUT_CONFLICT));
        var result = mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, allowed()).contentType(MediaType.APPLICATION_JSON).content(BODY).exchange();
        assertThat(result).hasStatus(409);
        assertThat(result).hasHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_DRAFT_GENERATION_002");
    }

    @Test
    void expiredUsesFeatureEnvelope() {
        given(useCase.register(any())).willThrow(new ProductDraftGenerationException(ProductDraftGenerationErrorCode.EXPIRED));
        var result = mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, allowed()).contentType(MediaType.APPLICATION_JSON).content(BODY).exchange();
        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_DRAFT_GENERATION_005");
    }
}
