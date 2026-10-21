package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.application.exception.ActivePaymentConflictException;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import java.util.Optional;

/**
 * 결제 시도 저장소. 호출자의 트랜잭션 안에서 돈다 — PG 호출은 이 트랜잭션 밖이다 ({@code payment-guide.md} §3-3).
 */
public interface PaymentRepositoryPort {

    /**
     * 결제 시도를 저장하고 바로 내보낸다(flush) — 충돌이 커밋 시점이 아니라 이 호출에서 드러나 호출자가 바꿀 수 있게.
     *
     * <ul>
     *   <li>{@code payment.version() == null} — 새 결제 시도를 INSERT 한다.</li>
     *   <li>그 밖 — 현재 버전이 {@code payment.version()} 일 때만 바꾸고 버전을 하나 올린다.</li>
     * </ul>
     *
     * @return 저장한 결제 시도 — 올라간 버전을 담는다. 다음 전이는 이 값으로 한다
     * @throws ActivePaymentConflictException 같은 주문에 활성 결제 시도가 이미 있을 때 ({@code uk_payment_active_order_id})
     * @throws org.springframework.dao.OptimisticLockingFailureException 버전 충돌 — 다른 요청(동시 confirm · 재조회 · 웹훅)이 먼저 바꿨다. 다시 읽어 현재 상태로 판단한다
     * @throws org.springframework.dao.DataIntegrityViolationException 그 밖의 제약 위반 — 코드 오류다. 원인 없이 제약 이름만 싣는다
     */
    Payment save(Payment payment);

    Optional<Payment> findById(long paymentId);

    Optional<Payment> findByPgOrderId(String pgOrderId);

    /** 주문의 활성(FAILED · ABORTED 가 아닌) 결제 시도. 많아야 하나다 ({@link Payment#activeOrderId()}). */
    Optional<Payment> findActiveByOrderId(long orderId);
}
