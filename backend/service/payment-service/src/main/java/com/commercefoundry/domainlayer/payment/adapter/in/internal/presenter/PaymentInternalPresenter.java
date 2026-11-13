package com.commercefoundry.domainlayer.payment.adapter.in.internal.presenter;

import com.commercefoundry.commerce.support.web.MoneyItems;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelExternalRefundResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentCancelVoidResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentInternalResponse;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelExternalRefundInfo;
import com.commercefoundry.domainlayer.payment.application.info.PaymentCancelVoidInfo;
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

    /** 무환불 판정 (#691). 판정 · 이유는 제어 코드 문자열로 낸다. */
    public PaymentCancelVoidResponse toVoidResponse(PaymentCancelVoidInfo info) {
        return PaymentCancelVoidResponse.builder()
            .paymentId(String.valueOf(info.paymentId()))
            .claimId(String.valueOf(info.claimId()))
            .outcome(info.outcome().name())
            .reason(info.reason().name())
            .paymentStatus(info.paymentStatus().toMetadata())
            .retainedAmount(MoneyItems.of(info.retainedAmount()))
            .sealed(info.sealed())
            .build();
    }

    /** 외부 확인 환불 기록 (#693). 판정 · 이유 · 채널은 제어 코드 문자열로 낸다. */
    public PaymentCancelExternalRefundResponse toExternalRefundResponse(PaymentCancelExternalRefundInfo info) {
        return PaymentCancelExternalRefundResponse.builder()
            .paymentId(String.valueOf(info.paymentId()))
            .claimId(String.valueOf(info.claimId()))
            .outcome(info.outcome().name())
            .reason(info.reason().name())
            .channel(info.channel() == null ? null : info.channel().name())
            .paymentStatus(info.paymentStatus().toMetadata())
            .refundedAmount(MoneyItems.of(info.refundedAmount()))
            .retainedAmount(MoneyItems.of(info.retainedAmount()))
            .build();
    }
}
