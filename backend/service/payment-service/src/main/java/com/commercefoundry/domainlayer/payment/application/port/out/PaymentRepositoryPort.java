package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException;
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
     * <p>호출자 규칙:
     * <ul>
     *   <li>전이 결과가 원래 인스턴스와 <b>같은 인스턴스</b>면(멱등 no-op — {@code markOrderSynced} 재호출 등) 저장하지 않는다. 저장하면 버전만 오르고
     *       동시 요청과 쓸데없이 부딪힌다.</li>
     *   <li>충돌 뒤 현재 상태를 다시 읽을 때는 <b>새 트랜잭션</b>에서 읽는다 — PostgreSQL 은 오류 난 트랜잭션을 중단(aborted)시켜 같은 트랜잭션의 다음 문장이 실패한다.</li>
     * </ul>
     *
     * @return 저장한 결제 시도 — 올라간 버전을 담는다. 다음 전이는 이 값으로 한다
     * @throws PaymentPersistenceConflictException 업무로 다룰 충돌 — 활성 슬롯({@code ACTIVE_SLOT}) · 결제키 중복({@code PAYMENT_KEY}) · 버전 충돌({@code VERSION}).
     *     원인 예외는 싣지 않는다
     * @throws org.springframework.dao.DataIntegrityViolationException 그 밖의 제약 위반(check · NOT NULL · PG 주문번호 · 기본 키) — 코드 오류다. 원인 없이 제약 이름만 싣는다
     */
    Payment save(Payment payment);

    Optional<Payment> findById(long paymentId);

    Optional<Payment> findByPgOrderId(String pgOrderId);

    /** 주문의 활성(FAILED · ABORTED · LATE_APPROVED 가 아닌) 결제 시도. 많아야 하나다 ({@link Payment#activeOrderId()}). */
    Optional<Payment> findActiveByOrderId(long orderId);
}
