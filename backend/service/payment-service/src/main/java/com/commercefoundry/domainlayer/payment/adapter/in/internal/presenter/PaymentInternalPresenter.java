package com.commercefoundry.domainlayer.payment.adapter.in.internal.presenter;

import com.commercefoundry.commerce.support.web.MoneyItems;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentInternalResponse;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import org.springframework.stereotype.Component;

/**
 * 결제 시도 → 서비스 간 결제 조회 응답. 식별자 {@code long → String} · {@code Money → MoneyItem} · enum → metadata 변환만 한다.
 * 결제키 · 멱등 키 · 주문명 · PG 원문 · 실패 사유는 싣지 않는다 ({@link PaymentInternalResponse}).
 */
@Component
public class PaymentInternalPresenter {

    public PaymentInternalResponse toResponse(Payment payment) {
        return PaymentInternalResponse.builder()
            .paymentId(String.valueOf(payment.id()))
            .orderId(String.valueOf(payment.orderId()))
            .memberId(String.valueOf(payment.memberId()))
            .status(payment.status().toMetadata())
            .amount(MoneyItems.of(payment.amount()))
            .method(payment.method() == null ? null : payment.method().toMetadata())
            .approvedAt(payment.approvedAt())
            .build();
    }
}
