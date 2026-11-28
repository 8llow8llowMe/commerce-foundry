package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 정산 대사용 원장 벌크 조회 (#219, {@code payment-guide.md} §10). 모두 IN 절 · 기간 한 번씩이다 — 결제마다 단건 조회하지 않는다 ({@code coding-conventions.md} §9-6).
 * 호출자의 트랜잭션에 참여한다.
 */
public interface PaymentLedgerQueryPort {

    /** 결제키가 목록에 있는 결제 시도. 결제키는 유니크({@code uk_payment_payment_key})라 키마다 많아야 하나다. 빈 목록이면 질의하지 않는다. */
    List<Payment> findByPaymentKeys(Collection<String> paymentKeys);

    /** PG 승인 시각이 {@code [from, to)} 이고 결제 통화가 {@code currency} 인 결제 시도. 승인 시각은 승인된 상태에만 있다({@code ck_payment_approved_fields}). */
    List<Payment> findApprovedBetween(Instant from, Instant to, CurrencyCode currency);

    /** 결제 아이디가 목록에 있는 취소 원장 행 전부(상태 무관). 빈 목록이면 질의하지 않는다. */
    List<PaymentCancel> findCancelsByPaymentIds(Collection<Long> paymentIds);
}
