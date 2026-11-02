package com.commercefoundry.domainlayer.payment.adapter.in.internal.presenter;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.APPROVED_AT;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentInternalResponse;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 결제 시도 → 서비스 간 결제 조회 응답 — 아이디 문자열, 금액 MoneyItem, 상태 · 수단 metadata, 내리지 않는 칸. */
class PaymentInternalPresenterTest {

    private final PaymentInternalPresenter presenter = new PaymentInternalPresenter();

    @Test
    @DisplayName("DONE — 아이디 셋은 문자열, 상태 · 수단은 metadata, 금액은 최소 단위 정수와 통화, 승인 시각 그대로")
    void done() {
        PaymentInternalResponse response = presenter.toResponse(PaymentFixtures.done());

        assertThat(response.paymentId()).isEqualTo(Long.toString(PaymentFixtures.PAYMENT_ID));
        assertThat(response.orderId()).isEqualTo(Long.toString(PaymentFixtures.ORDER_ID));
        assertThat(response.memberId()).isEqualTo(Long.toString(PaymentFixtures.MEMBER_ID));
        assertThat(response.status().code()).isEqualTo("DONE");
        assertThat(response.amount().amount()).isEqualTo(129_000L);
        assertThat(response.amount().currency()).isEqualTo("KRW");
        assertThat(response.method().code()).isEqualTo("EASY_PAY");
        assertThat(response.approvedAt()).isEqualTo(APPROVED_AT);
    }

    @Test
    @DisplayName("승인 전(READY) · 거절(FAILED)은 method · approvedAt 이 null 이다")
    void notApproved() {
        PaymentInternalResponse ready = presenter.toResponse(PaymentFixtures.ready());
        PaymentInternalResponse failed = presenter.toResponse(PaymentFixtures.failed());

        assertThat(ready.status().code()).isEqualTo("READY");
        assertThat(ready.method()).isNull();
        assertThat(ready.approvedAt()).isNull();
        assertThat(failed.status().code()).isEqualTo("FAILED");
        assertThat(failed.method()).isNull();
        assertThat(failed.approvedAt()).isNull();
    }

    @Test
    @DisplayName("응답 칸은 진위 확인에 필요한 일곱 개뿐이다 — 결제키 · 멱등 키 · 주문명 · PG 원문 · 실패 사유 칸이 없다")
    void exposesOnlyVerificationFields() {
        assertThat(Arrays.stream(PaymentInternalResponse.class.getRecordComponents()).map(RecordComponent::getName))
            .containsExactly("paymentId", "orderId", "memberId", "status", "amount", "method", "approvedAt");
    }
}
