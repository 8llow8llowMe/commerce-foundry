package com.commercefoundry.domainlayer.payment.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.payment.adapter.in.admin.dto.item.PaymentAdminItem;
import com.commercefoundry.domainlayer.payment.application.command.PaymentAdminSearchCommand;
import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminStatusFilter;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentAdminUseCase;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(PaymentAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class PaymentAdminControllerTest {
    private static final String PATH = "/api/v1/admin/payments";
    @Autowired private MockMvcTester mvc;
    @MockitoBean private PaymentAdminUseCase useCase;

    @Test
    void adminReceivesPageAndSafeContractAndAllFilters() {
        PaymentAdminItem item = new PaymentAdminItem("4611686018427387904", "4611686018427387905", PaymentStatus.UNKNOWN.toMetadata(),
            MoneyItem.of(129000, "KRW"), null, null, false, null, Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), null, null);
        given(useCase.search(any())).willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));
        var result = mvc.get().uri(PATH + "?status=UNKNOWN&orderId=100&orderSynced=false").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].paymentId").isEqualTo("4611686018427387904");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].amount.amount").isEqualTo(129000);
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].status.code").isEqualTo("UNKNOWN");
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].method").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.totalElements").isEqualTo(1);
        verify(useCase).search(new PaymentAdminSearchCommand(PaymentAdminStatusFilter.UNKNOWN, 100L, false, 0, 20));
        assertThat(Arrays.stream(PaymentAdminItem.class.getRecordComponents()).map(RecordComponent::getName)).containsExactly(
            "paymentId", "orderId", "status", "amount", "method", "unknownReason", "orderSynced", "orderSyncFailureCode",
            "createdAt", "updatedAt", "approvalStartedAt", "approvedAt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?status=REFUND_FAILED"})
    void anonymousAndCustomerCannotRead(String query) {
        assertThat(mvc.get().uri(PATH + query).exchange()).hasStatus(401);
        assertThat(mvc.get().uri(PATH + query).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange()).hasStatus(403);
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @CsvSource({"orderId=0,PAYMENT_102", "orderId=-1,PAYMENT_102", "page=-1,PAYMENT_116", "size=0,PAYMENT_117", "size=101,PAYMENT_117",
        "status=invalid,PAYMENT_198", "orderSynced=invalid,PAYMENT_198", "orderId=abc,PAYMENT_198"})
    void invalidFiltersFailBeforeQuery(String query, String code) {
        var result = mvc.get().uri(PATH + "?" + query).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();
        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DONE", "CANCELED"})
    void refundFailedIsAcceptedAndResponseKeepsActualPaymentStatus(String actualStatus) {
        PaymentAdminItem item = new PaymentAdminItem("4611686018427387904", "4611686018427387905", PaymentStatus.valueOf(actualStatus).toMetadata(),
            MoneyItem.of(129000, "KRW"), null, null, false, null, Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), null, null);
        given(useCase.search(any())).willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));
        var result = mvc.get().uri(PATH + "?status=REFUND_FAILED&orderId=100&orderSynced=false")
            .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange();
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].status.code").isEqualTo(actualStatus);
        verify(useCase).search(new PaymentAdminSearchCommand(PaymentAdminStatusFilter.REFUND_FAILED, 100L, false, 0, 20));
    }

    @Test
    void defaultRequestUsesNoImplicitStatusOrReplyFilter() {
        given(useCase.search(any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
        assertThat(mvc.get().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange()).hasStatusOk();
        verify(useCase).search(new PaymentAdminSearchCommand(null, null, null, 0, 20));
    }

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }
}
