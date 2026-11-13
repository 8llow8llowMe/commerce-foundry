package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import java.util.List;
import java.util.Optional;

public interface PaymentCancelPersistencePort {
    Optional<Payment> lockPayment(long paymentId);
    Optional<PaymentCancel> findByClaimId(long claimId);
    /** Auto intent owns this positive ID; the order claim path must never reuse it. */
    boolean isAutoClaim(long claimId);
    List<PaymentCancel> findByPaymentId(long paymentId);
    /** Strict INSERT and flush; never reads or updates an existing claim. */
    PaymentCancel insertNewAutoClaim(PaymentCancel cancel);
    /**
     * VOIDED 봉인 행의 엄격 INSERT + flush (#691). 기존 행을 읽거나 고치지 않는다.
     *
     * @throws com.commercefoundry.domainlayer.payment.application.exception.PaymentException 409 {@code PAYMENT_012} — 같은 claimId 행이 먼저 커밋됐다
     */
    PaymentCancel insertVoided(PaymentCancel cancel);
    /**
     * 외부 확인 환불 기록 + flush (#693). 행이 없으면 엄격 INSERT, 있으면 {@link PaymentCancel.Status#canBecome} 표대로 버전 확인 UPDATE 다.
     * 결제 행 잠금 아래에서만 부른다. 외부 확인 환불 상태를 쓰는 유일한 경로다.
     *
     * <p><b>전제</b> — UPDATE 의 {@code @Version} 확인은 <b>같은 트랜잭션에서 먼저 읽은 행</b>(호출자의 {@link #findByClaimId} 결과가 올라간 영속성 컨텍스트)의 버전
     * 기준이다. 판정에 쓴 행을 읽은 트랜잭션 안에서 부르지 않으면 이 확인은 판정 이후의 변경을 잡지 못한다.
     *
     * @throws com.commercefoundry.domainlayer.payment.application.exception.PaymentException 409 {@code PAYMENT_012} — INSERT 가 먼저 커밋된 같은 claimId 와
     *     부딪쳤거나 UPDATE 가 낙관 락에서 졌다
     */
    PaymentCancel recordExternalRefund(PaymentCancel cancel);
    /**
     * 행이 없으면 만들고, 있으면 상태만 바꾼다. 기존 행의 상태 변경은 {@link PaymentCancel.Status#canBecome} 표 안에서만 허용한다.
     * 외부 확인 환불 상태({@code EXTERNAL_PG_REFUNDED} · {@code OFF_PG_REFUNDED})는 받지 않는다 — {@link #recordExternalRefund} 로만 쓴다.
     *
     * @throws IllegalArgumentException 외부 확인 환불 상태를 넘겼다(호출자 버그)
     * @throws com.commercefoundry.domainlayer.payment.application.exception.PaymentException 409 {@code PAYMENT_019} 결제 · 금액 · 통화가 다름 ·
     *     409 {@code PAYMENT_016} 표에 없는 전이
     */
    PaymentCancel save(PaymentCancel cancel);
}
