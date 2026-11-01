package com.commercefoundry.domainlayer.payment.adapter.in.web.presenter;

import com.commercefoundry.commerce.support.web.MoneyItems;
import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.item.PaymentFailureItem;
import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.response.PaymentPrepareResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.response.PaymentResponse;
import com.commercefoundry.domainlayer.payment.application.info.PaymentPrepareInfo;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import org.springframework.stereotype.Component;

/**
 * 결제 시도 → 고객 응답. 식별자 {@code long → String} · {@code Money → MoneyItem} · enum → metadata 변환 경계다 ({@code architecture-guide.md} §3 Presenter).
 * 결제키 · 미확정 이유 · 주문 반영 여부 · 승인 시작 시각 · 고객 멱등 키는 내리지 않는다. 실패 사유는 결제 실패(FAILED)일 때만 싣는다 — 늦은 승인(LATE_APPROVED)의
 * 이력 거절 코드는 고객 응답이 아니다.
 */
@Component
public class PaymentWebPresenter {

    public PaymentResponse toResponse(Payment payment) {
        return PaymentResponse.builder()
            .paymentId(String.valueOf(payment.id()))
            .orderId(String.valueOf(payment.orderId()))
            .pgOrderId(payment.pgOrderId())
            .status(payment.status().toMetadata())
            .amount(MoneyItems.of(payment.amount()))
            .orderName(payment.orderName())
            .method(payment.method() == null ? null : payment.method().toMetadata())
            .easyPayProvider(payment.easyPayProvider())
            .approvedAt(payment.approvedAt())
            .failure(failureOf(payment))
            .build();
    }

    public PaymentPrepareResponse toPrepareResponse(PaymentPrepareInfo info) {
        PaymentResponse payment = toResponse(info.payment());
        return PaymentPrepareResponse.builder()
            .paymentId(payment.paymentId())
            .orderId(payment.orderId())
            .pgOrderId(payment.pgOrderId())
            .status(payment.status())
            .amount(payment.amount())
            .orderName(payment.orderName())
            .method(payment.method())
            .easyPayProvider(payment.easyPayProvider())
            .approvedAt(payment.approvedAt())
            .failure(payment.failure())
            .paymentDueAt(info.paymentDueAt())
            .build();
    }

    private static PaymentFailureItem failureOf(Payment payment) {
        if (payment.status() != PaymentStatus.FAILED) {
            return null;
        }
        return new PaymentFailureItem(payment.failureCode(), payment.failureMessage());
    }
}
