package com.commercefoundry.domainlayer.settlement.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.settlement.adapter.in.internal.dto.response.SettlementReconcileResponse;
import com.commercefoundry.domainlayer.settlement.application.command.SettlementReconcileCommand;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementErrorCode;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementException;
import com.commercefoundry.domainlayer.settlement.application.port.in.SettlementReconcileInternalUseCase;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDayStatus;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** 정산 대사 internal 진입 (#219) — 허용 호출자 · 검증 · 오류 봉투 · 응답 칸. UseCase 는 가짜다(판정은 Facade · Processor 시험). */
@WebMvcTest(SettlementReconcileInternalController.class)
@Import({InternalResourceServerConfigurer.class, InternalTokenPropertiesConfig.class, JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class})
class SettlementReconcileInternalControllerTest {

    private static final String PATH = "/internal/v1/settlements/reconcile";
    private static final String SELF = "payment-service";
    private static final LocalDate SOLD_DATE = LocalDate.parse("2026-11-20");

    @Autowired MockMvcTester mvc;
    @MockitoBean SettlementReconcileInternalUseCase useCase;

    @Test
    void operationsGetsSummaryWithNoStoreAndNoIdentifiers() {
        given(useCase.reconcile(new SettlementReconcileCommand(SOLD_DATE))).willReturn(new SettlementReconcileResponse(SOLD_DATE, 3,
            MoneyItem.of(-12_000, "KRW"), 2, 1, 1, 4, SettlementDayStatus.RECONCILED.toMetadata()));

        MvcTestResult result = post(service("operations-service"), "{\"soldDate\":\"2026-11-20\"}");

        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(result).bodyJson().extractingPath("$.dataBody").asMap().containsOnlyKeys("soldDate", "itemCount", "pgAmountTotal", "mismatchCount",
            "newMismatchCount", "autoClearedCount", "graceDeferredCount", "status");
        assertThat(result).bodyJson().extractingPath("$.dataBody.soldDate").isEqualTo("2026-11-20");
        assertThat(result).bodyJson().extractingPath("$.dataBody.pgAmountTotal.amount").asNumber().isEqualTo(-12_000);
        assertThat(result).bodyJson().extractingPath("$.dataBody.pgAmountTotal.currency").isEqualTo("KRW");
        assertThat(result).bodyJson().extractingPath("$.dataBody.graceDeferredCount").asNumber().isEqualTo(4);
        assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("RECONCILED");
        verify(useCase).reconcile(new SettlementReconcileCommand(SOLD_DATE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"order-service", "auth-service", "product-service", "delivery-service", "support-service"})
    void everyOtherServiceIsForbidden(String caller) {
        SecurityEnvelopeAssertions.assertEnvelope(post(service(caller), "{\"soldDate\":\"2026-11-20\"}").getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @EnumSource(MemberRole.class)
    void userTokensCannotReconcile(MemberRole role) {
        String token = "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
        SecurityEnvelopeAssertions.assertEnvelope(post(token, "{\"soldDate\":\"2026-11-20\"}").getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
        verifyNoInteractions(useCase);
    }

    @Test
    void anonymousIsUnauthorized() {
        SecurityEnvelopeAssertions.assertEnvelope(post(null, "{\"soldDate\":\"2026-11-20\"}").getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{}|SETTLEMENT_101|soldDate",
        "{\"soldDate\":null}|SETTLEMENT_101|soldDate",
        "{\"soldDate\":\"2026-13-01\"}|PAYMENT_100|soldDate",
        "{\"soldDate\":\"2026/11/20\"}|PAYMENT_100|soldDate"
    })
    void missingAndMalformedDatesAre400(String body, String code, String field) {
        MvcTestResult result = post(service("operations-service"), body);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
        verifyNoInteractions(useCase);
    }

    @Test
    void brokenJsonIs400WithServiceFallbackCode() {
        MvcTestResult result = post(service("operations-service"), "{\"soldDate\":");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PAYMENT_100");
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @CsvSource({"SETTLEMENT_DISABLED, 409, SETTLEMENT_001", "RECONCILE_CONFLICT, 409, SETTLEMENT_002", "PG_COLLECTION_FAILED, 503, SETTLEMENT_003",
        "SOLD_DATE_OUT_OF_RANGE, 400, SETTLEMENT_102"})
    void domainErrorsUseSettlementEnvelope(SettlementErrorCode errorCode, int status, String code) {
        given(useCase.reconcile(any())).willThrow(new SettlementException(errorCode));

        MvcTestResult result = post(service("operations-service"), "{\"soldDate\":\"2026-11-20\"}");

        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    private MvcTestResult post(String token, String body) {
        var request = mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, token);
        }
        return request.exchange();
    }

    private static String service(String caller) {
        return TestServiceTokens.bearer(caller, SELF);
    }
}
