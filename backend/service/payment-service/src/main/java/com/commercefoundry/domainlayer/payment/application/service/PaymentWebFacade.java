package com.commercefoundry.domainlayer.payment.application.service;

import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.response.PaymentResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.web.presenter.PaymentWebPresenter;
import com.commercefoundry.domainlayer.payment.application.command.PaymentConfirmCommand;
import com.commercefoundry.domainlayer.payment.application.command.PaymentPrepareCommand;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentRetryLaterException;
import com.commercefoundry.domainlayer.payment.application.info.PaymentPrepareInfo;
import com.commercefoundry.domainlayer.payment.application.model.PayableOrder;
import com.commercefoundry.domainlayer.payment.application.model.PgApprovalOutcome;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentWebUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.OrderSummaryProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentGatewayProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAbortReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApprovalMismatchException;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.global.properties.PaymentGateProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 결제 준비 · 승인 · 조회 ({@code payment-guide.md} "고객 API 계약").
 *
 * <p><b>트랜잭션을 걸지 않는다({@code NEVER})</b> — 준비는 order-service Feign, 승인은 토스 PG 호출이 섞인다. 트랜잭션 안에서 부르면 DB 커넥션을 쥔 채 원격 응답을
 * 기다리고, PG 승인 뒤 롤백되면 "PG 는 승인했는데 우리 DB 에는 없는" 상태가 된다 ({@code architecture-guide.md} §3 Facade 예외 · {@code payment-guide.md} §3-3).
 * DB 구간은 {@link PaymentProcessor} 의 메서드 단위 트랜잭션이다. 저장 충돌 뒤 다시 읽기도 그래서 언제나 새 트랜잭션이다. 바깥 트랜잭션 안에서 불리면
 * 이 경계가 무너지므로 {@code NEVER} 로 막는다.
 *
 * <p>이 클래스가 하는 것: 분기(활성 시도 · 상태 · 멱등 키), 저장 충돌 · 도메인 예외를 결제 오류 코드로 번역, 충돌 뒤 재조회. 도메인 예외 메시지는 응답에 싣지 않는다.
 * 보안 경고 로그(금액 불일치 · 결제키 불일치)에는 결제 · 회원 아이디만 남긴다 — 결제키 · 금액 원문은 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class PaymentWebFacade implements PaymentWebUseCase {

    /** 결제사에 승인 요청을 보내지 못했을 때(READY 로 되돌림) 같은 요청을 다시 보내기까지 기다릴 시간. */
    static final Duration GATEWAY_RETRY_AFTER = Duration.ofSeconds(5);

    private final OrderSummaryProcessor orderSummaryProcessor;
    private final PaymentProcessor paymentProcessor;
    private final PaymentGatewayProcessor paymentGatewayProcessor;
    private final PaymentWebPresenter paymentWebPresenter;
    private final PaymentGateProperties paymentGateProperties;
    private final Clock clock;

    /**
     * 결제 준비. 업무 키는 주문 아이디 + 주문당 활성 결제 시도 하나(DB 유니크 키)다 — {@code Idempotency-Key} 를 쓰지 않는다.
     *
     * <p>결제 게이트({@code payment.enabled})가 닫혀 있으면 아무것도 부르지 않고 503 {@code PAYMENT_015} 다.
     *
     * <ol>
     *   <li>주문 요약(order-service, 트랜잭션 밖) — 없음 404 · 결제 대기 아님 409 · KRW 아님 409 · 기한 임박 409</li>
     *   <li>활성 시도 없음 → 새 READY (201)</li>
     *   <li>READY 이고 금액 · 주문명이 같음 → 그대로 (200, 저장 없음). 결제키 있는 되돌린 READY 도 같다</li>
     *   <li>READY · 결제키 없음 · 금액이나 주문명이 다름 → 한 트랜잭션에서 버리고(ABORTED) 새 READY (201)</li>
     *   <li>READY · 결제키 있음 · 금액이 다름 → 409 {@code PAYMENT_012} — 이전 결제키가 승인됐을 수 있어 조회 없이 버리지 않는다. 주문명만 다르면 그대로(200)</li>
     *   <li>IN_PROGRESS · UNKNOWN · DONE 등 → 그 시도를 그대로 (200)</li>
     *   <li>저장이 겹치면(활성 슬롯 · 버전) 새 트랜잭션에서 다시 읽어 위 규칙으로 답할 수 있으면 200, 아니면 409 {@code PAYMENT_012}</li>
     * </ol>
     */
    @Override
    public PrepareResult prepare(long memberId, PaymentPrepareCommand command) {
        requireEnabled();
        PayableOrder order = orderSummaryProcessor.getPayableOrder(command.orderId(), memberId);
        Optional<Payment> active = paymentProcessor.findActive(order.orderId());
        if (active.isEmpty()) {
            return create(() -> paymentProcessor.prepare(memberId, order), memberId, order);
        }
        Payment current = active.get();
        requireSameMember(current, memberId);
        if (reusable(current, order)) {
            return existing(current, order);
        }
        if (current.paymentKey() != null) {
            // 금액이 바뀐 되돌린 READY — 이전 결제키가 승인됐을 수 있어 PG 조회 없이 버리지 않는다 (payment-guide.md §5)
            log.warn("payment prepare blocked by reverted ready with other amount. paymentId={}, orderId={}", current.id(), current.orderId());
            throw new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT);
        }
        PaymentAbortReason reason = current.amount().equals(order.totalAmount()) ? PaymentAbortReason.REPREPARED : PaymentAbortReason.AMOUNT_CHANGED;
        return create(() -> paymentProcessor.replace(current, reason, memberId, order), memberId, order);
    }

    /**
     * 결제 승인 ({@code payment-guide.md} §3 · §3-2 · §4). 고객 {@code Idempotency-Key} 는 결제 행에 기록한다 — (회원, 키) 유니크 키가 키의 결과를 가리킨다.
     *
     * <p>결제 게이트({@code payment.enabled})가 닫혀 있으면 아무것도 부르지 않고 503 {@code PAYMENT_015} 다.
     *
     * <ol>
     *   <li>회원의 결제 시도(PG 주문번호) — 없거나 남의 것 404 {@code PAYMENT_001}</li>
     *   <li>이 키로 다른 결제 시도를 이미 시작했으면 409 {@code PAYMENT_014}</li>
     *   <li>READY 가 아니면 저장 없이 답한다({@link #answerWithoutApproval}) — 같은 키의 재요청은 처음 결과(현재 상태) 그대로</li>
     *   <li>금액 · 통화가 결제 시도와 다르면 PG 를 부르지 않고 400 {@code PAYMENT_008} + 보안 경고</li>
     *   <li>주문을 다시 확인한다(order-service, 트랜잭션 밖) — 없음 404 · 결제 대기 아님 409 · 기한 경과 409 · 장애 503 · 금액이 결제 시도와 다름 409 {@code PAYMENT_012}.
     *       어느 쪽이든 PG 를 부르지 않고 결제 행을 바꾸지 않는다(READY 그대로 — 정리는 #15)</li>
     *   <li>READY → IN_PROGRESS 저장(트랜잭션 1, 커밋) — 동시 요청은 버전에서 진다</li>
     *   <li>PG 승인 요청 — <b>트랜잭션 밖</b></li>
     *   <li>결과 기록(트랜잭션 2) — DONE · FAILED · UNKNOWN 이면 200, READY(보내지 못함)면 503 {@code PAYMENT_010} + {@code Retry-After}</li>
     * </ol>
     */
    @Override
    public PaymentResponse confirm(long memberId, UUID idempotencyKey, PaymentConfirmCommand command) {
        requireEnabled();
        String key = idempotencyKey.toString();
        Payment current = paymentProcessor.getOwnedByPgOrderId(memberId, command.pgOrderId());
        Optional<Payment> keyed = paymentProcessor.findByApprovalKey(memberId, key);
        if (keyed.isPresent() && keyed.get().id() != current.id()) {
            throw new PaymentException(PaymentErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (current.status() != PaymentStatus.READY) {
            return paymentWebPresenter.toResponse(answerWithoutApproval(current, command, key));
        }
        if (!sameAmount(current, command)) {
            log.warn("security: payment confirm amount mismatch. paymentId={}, memberId={}", current.id(), memberId);
            throw new PaymentException(PaymentErrorCode.AMOUNT_MISMATCH);
        }
        requireOrderStillPayable(current, memberId);
        Payment inProgress = startApproval(current, command, key, memberId);
        if (inProgress.status() != PaymentStatus.IN_PROGRESS) {
            // 동시 요청이 먼저 승인을 시작 · 기록했다 — 다시 읽은 상태로 답한다
            return paymentWebPresenter.toResponse(inProgress);
        }
        PgApprovalOutcome outcome = paymentGatewayProcessor.approve(inProgress);
        Payment recorded = recordApprovalResult(inProgress, outcome, memberId);
        if (recorded.status() == PaymentStatus.READY) {
            throw new PaymentRetryLaterException(PaymentErrorCode.GATEWAY_UNAVAILABLE, GATEWAY_RETRY_AFTER);
        }
        return paymentWebPresenter.toResponse(recorded);
    }

    /** 회원의 결제 상태. 읽기 트랜잭션은 Processor 가 연다. */
    @Override
    public PaymentResponse getPayment(long memberId, long paymentId) {
        return paymentWebPresenter.toResponse(paymentProcessor.getOwned(paymentId, memberId));
    }

    /**
     * READY 가 아닌 결제 시도에 온 승인 요청 — 저장하지 않고 답한다.
     *
     * <ul>
     *   <li>DONE · UNKNOWN · FAILED(그리고 승인 계열 확장 상태)에 같은 키 · 같은 결제키 → 현재 상태(멱등). 같은 키인데 결제키가 다르면 409 {@code PAYMENT_014}.
     *       DONE · UNKNOWN(취소 계열 포함)에 같은 키 · 같은 결제키인데 금액 · 통화가 다르면 409 {@code PAYMENT_014} + 보안 경고</li>
     *   <li>다른 키 — DONE · UNKNOWN 등에 같은 결제키면 현재 상태(새 키로 재확인), 다른 결제키면 400 {@code PAYMENT_009} + 보안 경고. FAILED 면 409 {@code PAYMENT_006}</li>
     *   <li>IN_PROGRESS → 409 {@code PAYMENT_007}(같은 키든 아니든 — 처리 중인 요청은 409)</li>
     *   <li>ABORTED · LATE_APPROVED → 409 {@code PAYMENT_006}</li>
     * </ul>
     *
     * @return 그대로 돌려줄 결제 시도 — READY 면 호출하지 않는다
     */
    private Payment answerWithoutApproval(Payment current, PaymentConfirmCommand command, String key) {
        PaymentStatus status = current.status();
        if (status == PaymentStatus.IN_PROGRESS) {
            throw new PaymentException(PaymentErrorCode.APPROVAL_IN_PROGRESS);
        }
        if (status == PaymentStatus.ABORTED || status == PaymentStatus.LATE_APPROVED) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_CLOSED);
        }
        boolean samePaymentKey = command.paymentKey().equals(current.paymentKey());
        if (key.equals(current.approvalIdempotencyKey())) {
            if (!samePaymentKey) {
                throw new PaymentException(PaymentErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            if (status != PaymentStatus.FAILED && !sameAmount(current, command)) {
                // 같은 키 · 같은 결제키인데 본문(금액 · 통화)이 다르다 — 같은 요청의 재전송이 아니다 (api-design-guide.md §6)
                log.warn("security: payment confirm replay with other amount. paymentId={}, memberId={}", current.id(), current.memberId());
                throw new PaymentException(PaymentErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return current;
        }
        if (status == PaymentStatus.FAILED) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_CLOSED);
        }
        if (!samePaymentKey) {
            log.warn("security: payment confirm payment key mismatch. paymentId={}, memberId={}, status={}", current.id(), current.memberId(), status);
            throw new PaymentException(PaymentErrorCode.PAYMENT_KEY_MISMATCH);
        }
        return current;
    }

    /**
     * READY → IN_PROGRESS 를 저장한다(트랜잭션 1). 버전에서 지면 다른 요청이 먼저 바꾼 것이다 — 새 트랜잭션에서 다시 읽어 {@link #answerWithoutApproval} 로 답한다.
     *
     * @return 저장한 IN_PROGRESS, 또는 다시 읽어 그대로 돌려줄 결제 시도(READY 가 아님)
     */
    private Payment startApproval(Payment ready, PaymentConfirmCommand command, String key, long memberId) {
        try {
            return paymentProcessor.startApproval(ready, command.paymentKey(), key, clock.instant());
        } catch (PaymentApprovalMismatchException mismatch) {
            // 되돌린 READY 에 다른 결제키 — 이전 결제키가 승인됐을 수 있어 바꾸지 않는다
            log.warn("security: payment confirm payment key mismatch. paymentId={}, memberId={}, status=READY", ready.id(), memberId);
            throw new PaymentException(PaymentErrorCode.PAYMENT_KEY_MISMATCH);
        } catch (PaymentPersistenceConflictException conflict) {
            return switch (conflict.getKind()) {
                case VERSION -> {
                    Payment reloaded = paymentProcessor.getOwned(ready.id(), memberId);
                    if (reloaded.status() == PaymentStatus.READY) {
                        // 다른 요청이 시작했다가 보내지 못해 되돌렸다 — 지금 다시 시작하지 않고 재시도를 맡긴다
                        throw new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT);
                    }
                    yield answerWithoutApproval(reloaded, command, key);
                }
                case PAYMENT_KEY -> {
                    // 다른 결제 시도의 결제키를 이 시도에 붙이려 했다 — 변조 · 잘못된 짝
                    log.warn("security: payment confirm reused payment key of another attempt. paymentId={}, memberId={}", ready.id(), memberId);
                    throw new PaymentException(PaymentErrorCode.PAYMENT_KEY_MISMATCH);
                }
                case APPROVAL_KEY -> throw new PaymentException(PaymentErrorCode.IDEMPOTENCY_KEY_REUSED);
                case ACTIVE_SLOT -> throw new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT);
            };
        }
    }

    /**
     * PG 결과를 기록한다(트랜잭션 2). 버전에서 지면(그 사이 재조회 · 웹훅이 먼저 기록) 새 트랜잭션에서 다시 읽어 현재 상태로 답한다.
     * 그 밖의 저장 실패는 500 {@code PAYMENT_013} — PG 는 승인했을 수 있고 IN_PROGRESS 행이 복구 단서로 남는다 ({@code payment-guide.md} §3-3).
     */
    private Payment recordApprovalResult(Payment inProgress, PgApprovalOutcome outcome, long memberId) {
        try {
            return paymentProcessor.recordApprovalResult(inProgress, outcome);
        } catch (PaymentPersistenceConflictException conflict) {
            if (conflict.getKind() != PaymentPersistenceConflictException.Kind.VERSION) {
                log.error("payment approval result conflicted. paymentId={}, kind={}, outcome={}", inProgress.id(), conflict.getKind(), outcome);
                throw new PaymentException(PaymentErrorCode.APPROVAL_RECORD_FAILED, conflict);
            }
            // 재조회 · 웹훅이 먼저 기록한 정상 경합이다 — 다시 읽은 상태로 답한다
            log.warn("payment approval result conflicted. paymentId={}, kind={}, outcome={}", inProgress.id(), conflict.getKind(), outcome);
            return paymentProcessor.getOwned(inProgress.id(), memberId);
        } catch (PaymentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.error("payment approval result not recorded. paymentId={}, outcome={}, causeType={}",
                inProgress.id(), outcome, exception.getClass().getSimpleName());
            throw new PaymentException(PaymentErrorCode.APPROVAL_RECORD_FAILED, exception);
        }
    }

    /** 새 시도를 만든다. 겹치면 새 트랜잭션에서 다시 읽어 그대로 돌려줄 수 있는 시도면 200, 아니면 409 {@code PAYMENT_012}. */
    private PrepareResult create(Supplier<Payment> creation, long memberId, PayableOrder order) {
        try {
            return new PrepareResult(true, paymentWebPresenter.toPrepareResponse(new PaymentPrepareInfo(creation.get(), order.paymentDueAt())));
        } catch (PaymentPersistenceConflictException conflict) {
            Optional<Payment> reloaded = paymentProcessor.findActive(order.orderId());
            if (reloaded.isPresent() && reloaded.get().memberId() == memberId && reusable(reloaded.get(), order)) {
                return existing(reloaded.get(), order);
            }
            log.warn("payment prepare conflicted. orderId={}, kind={}", order.orderId(), conflict.getKind());
            throw new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT, conflict);
        }
    }

    /** 운영 결제 게이트 — 닫혀 있으면 DB · PG · order 를 부르기 전에 503 {@code PAYMENT_015}. #308 · #15 전에는 운영에서 켜지 않는다 ({@code payment-guide.md} §6). */
    private void requireEnabled() {
        if (!paymentGateProperties.enabled()) {
            throw new PaymentException(PaymentErrorCode.PAYMENTS_DISABLED);
        }
    }

    /**
     * 승인 직전 주문 재확인 — 결제 준비 뒤 주문이 취소 · 만료 · 결제됐으면 PG 에 승인을 보내지 않는다. 트랜잭션 밖이다(order-service 원격 호출).
     * 결제 행은 바꾸지 않는다 — READY 그대로 두고 정리는 #15 가 한다. 주문 금액은 주문 뒤 바뀌지 않으므로 결제 시도와 다르면 결함 신호라 409 {@code PAYMENT_012} + error 로그다.
     */
    private void requireOrderStillPayable(Payment ready, long memberId) {
        PayableOrder order = orderSummaryProcessor.getConfirmableOrder(ready.orderId(), memberId);
        if (!order.totalAmount().equals(ready.amount())) {
            log.error("payment confirm order amount differs from attempt. paymentId={}, orderId={}", ready.id(), ready.orderId());
            throw new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT);
        }
    }

    private PrepareResult existing(Payment payment, PayableOrder order) {
        return new PrepareResult(false, paymentWebPresenter.toPrepareResponse(new PaymentPrepareInfo(payment, order.paymentDueAt())));
    }

    /**
     * 활성 시도를 그대로 돌려줄 수 있는가 — READY 가 아니면(승인 중 · 확인 중 · 완료 등) 언제나, READY 면 금액 · 주문명이 주문과 같을 때,
     * 결제키 있는 되돌린 READY 는 금액만 같으면(주문명 차이로 승인 흔적을 버리지 않는다).
     */
    private static boolean reusable(Payment payment, PayableOrder order) {
        if (payment.status() != PaymentStatus.READY) {
            return true;
        }
        boolean sameAmount = payment.amount().equals(order.totalAmount());
        if (payment.paymentKey() != null) {
            return sameAmount;
        }
        return sameAmount && payment.orderName().equals(order.orderName());
    }

    /** 활성 시도의 회원은 주문의 회원이다 — 주문 요약이 소유를 확인했다. 다르면 데이터 이상이라 시도를 건드리지 않는다. */
    private static void requireSameMember(Payment payment, long memberId) {
        if (payment.memberId() != memberId) {
            log.error("payment active attempt belongs to other member. paymentId={}, orderId={}", payment.id(), payment.orderId());
            throw new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT);
        }
    }

    private static boolean sameAmount(Payment payment, PaymentConfirmCommand command) {
        return payment.amount().amount() == command.amount() && payment.amount().currency().name().equals(command.currency());
    }
}
