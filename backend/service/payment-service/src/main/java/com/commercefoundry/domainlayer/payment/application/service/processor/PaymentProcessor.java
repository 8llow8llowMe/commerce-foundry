package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.model.PayableOrder;
import com.commercefoundry.domainlayer.payment.application.model.PgApprovalOutcome;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentEventPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAbortReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApproval;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApprovalMismatchException;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 시도의 조회 · 전이 저장. 메서드 하나가 트랜잭션 하나다 — 결제 API 는 PG · order-service 호출이 섞여 Facade 에 트랜잭션을 걸지 않으므로
 * DB 구간만 여기서 좁게 연다 ({@code architecture-guide.md} §3 Facade 예외, {@code payment-guide.md} §3-3).
 *
 * <p>저장 충돌({@code PaymentPersistenceConflictException}) · 도메인 전이 예외는 여기서 바꾸지 않고 올린다 — 같은 버전 충돌이 준비 · 승인 시작 · 결과 기록에서
 * 뜻이 달라 Facade 가 새 트랜잭션에서 다시 읽고 응답을 정한다(충돌한 트랜잭션은 PostgreSQL 이 중단시켜 같은 트랜잭션에서 다시 읽을 수 없다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentProcessor {

    private final PaymentRepositoryPort paymentRepositoryPort;
    private final PaymentEventPort paymentEventPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 결제 시도 — 회원을 거르지 않는다. 서비스 간 조회(internal, 허용 호출자 order-service) 전용이다. 고객 진입은 {@link #getOwned} 를 쓴다.
     * 없으면 404 {@code PAYMENT_001}.
     */
    @Transactional(readOnly = true)
    public Payment getUnscoped(long paymentId) {
        return paymentRepositoryPort.findById(paymentId).orElseThrow(() -> new PaymentException(PaymentErrorCode.NOT_FOUND_PAYMENT));
    }

    /** 회원의 결제 시도. 없거나 다른 회원의 것이면 404 {@code PAYMENT_001} — 존재를 숨긴다. */
    @Transactional(readOnly = true)
    public Payment getOwned(long paymentId, long memberId) {
        return paymentRepositoryPort.findById(paymentId)
            .filter(payment -> payment.memberId() == memberId)
            .orElseThrow(() -> new PaymentException(PaymentErrorCode.NOT_FOUND_PAYMENT));
    }

    /** PG 주문번호로 찾은 회원의 결제 시도. 없거나 다른 회원의 것이면 404 {@code PAYMENT_001}. */
    @Transactional(readOnly = true)
    public Payment getOwnedByPgOrderId(long memberId, String pgOrderId) {
        return paymentRepositoryPort.findByPgOrderId(pgOrderId)
            .filter(payment -> payment.memberId() == memberId)
            .orElseThrow(() -> new PaymentException(PaymentErrorCode.NOT_FOUND_PAYMENT));
    }

    /** 주문의 활성 결제 시도(FAILED · ABORTED · LATE_APPROVED 가 아닌 것). */
    @Transactional(readOnly = true)
    public Optional<Payment> findActive(long orderId) {
        return paymentRepositoryPort.findActiveByOrderId(orderId);
    }

    /** 회원이 이 고객 {@code Idempotency-Key} 로 승인을 시작한 결제 시도. */
    @Transactional(readOnly = true)
    public Optional<Payment> findByApprovalKey(long memberId, String idempotencyKey) {
        return paymentRepositoryPort.findByApprovalIdempotencyKey(memberId, idempotencyKey);
    }

    /**
     * 새 결제 시도(READY)를 만든다. 아이디(Snowflake)가 곧 PG 주문번호다.
     *
     * @throws com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException {@code ACTIVE_SLOT} — 같은 주문의 결제 준비가 겹쳤다
     */
    @Transactional
    public Payment prepare(long memberId, PayableOrder order) {
        return paymentRepositoryPort.save(newAttempt(memberId, order));
    }

    /**
     * 결제키 없는 READY 를 버리고(ABORTED) 같은 트랜잭션에서 새 READY 를 만든다 — 주문 금액 · 주문명이 바뀌었을 때다 ({@code payment-guide.md} §3-1).
     * 둘이 한 트랜잭션이라 활성 슬롯이 비는 순간이 없다.
     *
     * @throws com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException {@code VERSION} — 읽은 뒤 다른 요청이 그 READY 를 바꿨다.
     *     {@code ACTIVE_SLOT} — 다른 준비 요청이 먼저 새 시도를 만들었다
     * @throws com.commercefoundry.domainlayer.payment.domain.model.PaymentTransitionException 결제키가 있는 READY — 조회 없이 버릴 수 없다
     */
    @Transactional
    public Payment replace(Payment ready, PaymentAbortReason reason, long memberId, PayableOrder order) {
        paymentRepositoryPort.save(ready.abort(reason));
        return paymentRepositoryPort.save(newAttempt(memberId, order));
    }

    /**
     * READY → IN_PROGRESS 를 저장해 커밋한다 — PG 호출 <b>전</b>이다. 동시 승인 중 하나만 버전 검사를 통과하고, PG 승인 뒤 기록이 실패해도 이 행이 복구 단서다.
     *
     * @throws PaymentApprovalMismatchException 되돌린 READY 에 다른 결제키
     * @throws com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException {@code VERSION}(동시 승인) ·
     *     {@code PAYMENT_KEY}(다른 시도의 결제키) · {@code APPROVAL_KEY}(같은 고객 키로 다른 시도를 이미 시작)
     */
    @Transactional
    public Payment startApproval(Payment ready, String paymentKey, String idempotencyKey, Instant now) {
        return paymentRepositoryPort.save(ready.startApproval(paymentKey, idempotencyKey, now));
    }

    /**
     * PG 승인 결과를 기록한다 — PG 호출 <b>뒤</b>, 새 트랜잭션이다.
     *
     * <ul>
     *   <li>Approved → DONE. 금액 · 결제키가 어긋나면({@link PaymentApprovalMismatchException}) 승인으로 기록하지 않고 UNKNOWN({@code APPROVAL_MISMATCH}) + error 로그</li>
     *   <li>Rejected → FAILED</li>
     *   <li>Unknown → UNKNOWN + warn 로그 — 재조회로 확정한다</li>
     *   <li>NotSent → READY 로 되돌린다(결제키 유지) — 같은 결제키로 다시 승인할 수 있다</li>
     * </ul>
     *
     * <p>DONE 이면 같은 트랜잭션에서 {@code payment-approved} 를 outbox 에 넣는다({@link #saveApproved}, {@code payment-guide.md} §6). DONE 이 아니면 발행하지 않는다.
     * 발행이 실패하면 DONE 저장도 함께 롤백된다 — 결제는 IN_PROGRESS 로 남고 Facade 가 500 {@code PAYMENT_013} 으로 답한다(복구는 #15 의 재조회 몫이다 — 아직 없다).
     *
     * @throws com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException {@code VERSION} — 그 사이 재조회 · 웹훅이 먼저 바꿨다
     */
    @Transactional
    public Payment recordApprovalResult(Payment inProgress, PgApprovalOutcome outcome) {
        Payment next = switch (outcome) {
            case PgApprovalOutcome.Approved approved -> approveOrHold(inProgress, approved.approval());
            case PgApprovalOutcome.Rejected rejected -> inProgress.reject(rejected.failureCode(), rejected.failureMessage());
            case PgApprovalOutcome.Unknown unknown -> {
                log.warn("payment approval result unknown. paymentId={}, reason={}", inProgress.id(), unknown.reason());
                yield inProgress.markUnknown(unknown.reason());
            }
            case PgApprovalOutcome.NotSent notSent -> {
                log.warn("payment approval not sent. reverted to ready. paymentId={}, reason={}", inProgress.id(), notSent.reason());
                yield inProgress.revertToReady();
            }
        };
        return next.status() == PaymentStatus.DONE ? saveApproved(inProgress, next) : saveIfChanged(inProgress, next);
    }

    /**
     * DONE 저장과 승인 이벤트 발행을 한 묶음으로 둔다 — 호출자 트랜잭션 안에서만 부른다(둘이 함께 커밋 · 롤백된다). 지금은 승인 응답 기록({@link #recordApprovalResult})이
     * 쓰고, #15 의 UNKNOWN 재조회 확정({@code Payment#resolve} → DONE)도 이 메서드로 저장해야 한다 — 발행을 빠뜨리면 주문이 결제 완료로 바뀌지 않는다.
     *
     * <p>전이가 같은 인스턴스를 돌려주면(이미 DONE — 멱등 no-op) 저장도 발행도 하지 않는다. 같은 결제의 승인 이벤트가 두 번 나가지 않게 한다.
     * 패키지 공개다 — 재조회 확정이 생기기 전까지는 공개 메서드로 no-op 경로에 닿을 수 없어 테스트가 직접 부른다.
     */
    Payment saveApproved(Payment current, Payment approved) {
        if (approved == current) {
            return current;
        }
        Payment saved = paymentRepositoryPort.save(approved);
        paymentEventPort.approved(saved);
        return saved;
    }

    /** 승인 결과가 결제 시도와 어긋나면 승인으로 기록하지 않는다 — 돈이 빠졌을 수 있어 UNKNOWN 으로 두고 사람이 확인한다 ({@code payment-guide.md} §5). */
    private static Payment approveOrHold(Payment inProgress, PaymentApproval approval) {
        try {
            return inProgress.approve(approval);
        } catch (PaymentApprovalMismatchException mismatch) {
            log.error("payment approval does not match attempt. paymentId={}, field={}", inProgress.id(), mismatch.getField());
            return inProgress.markUnknown(PaymentUnknownReason.APPROVAL_MISMATCH);
        }
    }

    /** 전이가 같은 인스턴스를 돌려주면(멱등 no-op) 저장하지 않는다 — 버전만 오르고 동시 요청과 쓸데없이 부딪힌다 ({@code PaymentRepositoryPort#save}). */
    private Payment saveIfChanged(Payment current, Payment next) {
        return next == current ? current : paymentRepositoryPort.save(next);
    }

    private Payment newAttempt(long memberId, PayableOrder order) {
        return Payment.ready(snowflakeIdGenerator.generateId(), order.orderId(), memberId, order.totalAmount(), order.orderName());
    }
}
