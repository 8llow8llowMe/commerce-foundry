package com.commercefoundry.domainlayer.payment.adapter.in.web.presenter;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.APPROVED_AT;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.ORDER_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.ORDER_NAME;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.PAYMENT_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.response.PaymentPrepareResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.response.PaymentResponse;
import com.commercefoundry.domainlayer.payment.application.info.PaymentPrepareInfo;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 결제 시도 → 고객 응답 — 아이디 문자열, 금액 · 상태 · 수단 변환, 실패 사유는 FAILED 만, 내부 칸은 내리지 않는다. */
class PaymentWebPresenterTest {

    private final PaymentWebPresenter presenter = new PaymentWebPresenter();

    @Test
    @DisplayName("READY — 아이디는 문자열, 승인 칸(수단 · 제공자 · 승인 시각)과 실패 사유는 null")
    void ready() {
        PaymentResponse response = presenter.toResponse(PaymentFixtures.ready());

        assertThat(response.paymentId()).isEqualTo(Long.toString(PAYMENT_ID));
        assertThat(response.orderId()).isEqualTo(Long.toString(ORDER_ID));
        assertThat(response.pgOrderId()).isEqualTo(Long.toString(PAYMENT_ID));
        assertThat(response.status().code()).isEqualTo("READY");
        assertThat(response.status().name()).isEqualTo("결제 대기");
        assertThat(response.amount().amount()).isEqualTo(129_000L);
        assertThat(response.amount().currency()).isEqualTo("KRW");
        assertThat(response.orderName()).isEqualTo(ORDER_NAME);
        assertThat(response.method()).isNull();
        assertThat(response.easyPayProvider()).isNull();
        assertThat(response.approvedAt()).isNull();
        assertThat(response.failure()).isNull();
    }

    @Test
    @DisplayName("DONE — 수단 metadata · 제공자 · 승인 시각을 싣고 실패 사유는 null")
    void done() {
        PaymentResponse response = presenter.toResponse(PaymentFixtures.done());

        assertThat(response.status().code()).isEqualTo("DONE");
        assertThat(response.method().code()).isEqualTo("EASY_PAY");
        assertThat(response.method().name()).isEqualTo("간편결제");
        assertThat(response.easyPayProvider()).isEqualTo("카카오페이");
        assertThat(response.approvedAt()).isEqualTo(APPROVED_AT);
        assertThat(response.failure()).isNull();
    }

    @Test
    @DisplayName("FAILED 만 실패 사유(코드 · 문구)를 싣는다 — LATE_APPROVED 의 이력 거절 코드는 싣지 않는다")
    void failureOnlyWhenFailed() {
        PaymentResponse failed = presenter.toResponse(PaymentFixtures.failed());
        assertThat(failed.failure().code()).isEqualTo("REJECT_CARD_COMPANY");
        assertThat(failed.failure().message()).isEqualTo("결제를 완료하지 못했습니다. 다른 결제 수단으로 다시 시도해 주세요.");

        assertThat(presenter.toResponse(PaymentFixtures.lateApproved()).failure()).isNull();
        assertThat(presenter.toResponse(PaymentFixtures.unknown()).failure()).isNull();
    }

    @Test
    void neverReturnsStoredGatewayMessageAcrossResponseVariants() {
        for (String code : Arrays.asList("REJECT_CARD_COMPANY", "MERCHANT_CONFIGURATION_ERROR", "UNRECOGNIZED")) {
            for (String raw : Arrays.asList("merchant contract diagnostic", "<script>alert(1)</script>", null)) {
                var payment = PaymentFixtures.inProgress().reject(code, raw);
                assertThat(presenter.toResponse(payment).failure().code()).isEqualTo(code);
                assertThat(presenter.toResponse(payment).failure().message())
                    .isEqualTo("결제를 완료하지 못했습니다. 다른 결제 수단으로 다시 시도해 주세요.");
                assertThat(presenter.toPrepareResponse(new PaymentPrepareInfo(payment, Instant.now())).failure())
                    .isEqualTo(presenter.toResponse(payment).failure());
                assertThat(payment.failureMessage()).isEqualTo(raw);
            }
        }
    }

    @Test
    @DisplayName("응답에 결제키 · 미확정 이유 · 주문 반영 · 승인 시작 시각 · 고객 멱등 키 칸이 없다")
    void hidesInternalFields() {
        assertThat(Arrays.stream(PaymentResponse.class.getRecordComponents()).map(RecordComponent::getName))
            .containsExactly("paymentId", "orderId", "pgOrderId", "status", "amount", "orderName", "method", "easyPayProvider", "approvedAt", "failure");
        assertThat(Arrays.stream(PaymentPrepareResponse.class.getRecordComponents()).map(RecordComponent::getName))
            .containsExactly("paymentId", "orderId", "pgOrderId", "status", "amount", "orderName", "method", "easyPayProvider", "approvedAt", "failure",
                "paymentDueAt");
    }

    @Test
    @DisplayName("준비 응답은 결제 응답 칸에 주문의 결제 기한을 더한다")
    void prepareResponse() {
        Instant dueAt = Instant.parse("2026-10-21T01:30:00Z");

        PaymentPrepareResponse response = presenter.toPrepareResponse(new PaymentPrepareInfo(PaymentFixtures.ready(), dueAt));

        assertThat(response.paymentId()).isEqualTo(Long.toString(PAYMENT_ID));
        assertThat(response.status().code()).isEqualTo("READY");
        assertThat(response.amount().amount()).isEqualTo(129_000L);
        assertThat(response.paymentDueAt()).isEqualTo(dueAt);
    }
}
