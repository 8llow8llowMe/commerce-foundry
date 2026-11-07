package com.commercefoundry.domainlayer.payment.application.model;

import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;

/** Processor가 검증한 최초 원천 결과. 포트 전송 타입은 Facade로 넘기지 않는다. */
public record OrderPaymentReplySnapshot(
    long paymentId, long orderId, long memberId, boolean paid, String rejectionReason, Money amount, Instant approvedAt, Long stockReservationId
) { }
