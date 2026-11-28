package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentCancelPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelVoidQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 후 취소 거절 전 payment 무환불 판정 (#17 A4, {@code payment-guide.md} §8-1) — {@link PaymentCancelPort#confirmNoRefund} 를 부르는 곳은 이 Processor 하나다.
 * 어드민 거절 동작 한 번에 한 번만 부르고, 상세 조회 · 재시도 스케줄러 · 고객 요청은 부르지 않는다(payment 의 PG 잔액 조회가 결제 승인과 같은 서킷을 쓴다).
 *
 * <p><b>트랜잭션 밖에서만 부른다</b>({@code Propagation.NEVER}) — PG 잔액 조회를 기다리는 긴 원격 호출이라 DB 커넥션 · 주문 잠금을 쥔 채 부르지 않는다.
 * 본문은 클레임에 저장된 결제 아이디 · 주문 아이디 · 클레임 아이디 · 환불액이다(다시 계산하지 않는다).
 *
 * <p>분기는 결과의 {@code outcome} 만 본다 — 사유 코드는 로그 전용이다. {@code NO_REFUND_CONFIRMED} 만 통과하고 나머지는 예외로 거절을 막는다:
 * {@code REFUNDED} 409 {@code ORDER_052}(이미 갈라진 상태라 warn), {@code UNCONFIRMED} 409 {@code ORDER_053}, {@code UNAVAILABLE} · 포트 예외 503 {@code ORDER_054}.
 * payment 가 봉인했는지({@code sealed})는 포트 결과에 없어 "미확인인데 봉인" 같은 구분은 하지 않는다 — 봉인은 확정({@code NO_REFUND_CONFIRMED})에서만 확인된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimCancellationVoidProcessor {

    private final PaymentCancelPort paymentCancelPort;

    /**
     * 무환불 확정을 받는다. 확정이면 그대로 돌아가고, 아니면 거절하지 않도록 예외를 던진다.
     *
     * @param claim 자격 확인(tx1)을 통과한 저장된 취소 클레임
     * @throws OrderException {@code CANCELLATION_REJECTION_REFUND_FOUND} · {@code CANCELLATION_REJECTION_UNVERIFIED} · {@code CANCELLATION_REJECTION_CHECK_UNAVAILABLE}
     */
    @Transactional(propagation = Propagation.NEVER)
    public void confirmNoRefund(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        long paymentId = Objects.requireNonNull(claim.paymentId(), "paymentId");
        PaymentCancelVoidQueryResult result;
        try {
            result = paymentCancelPort.confirmNoRefund(paymentId, claim.orderId(), claim.id(), claim.expectedRefund());
        } catch (RuntimeException exception) {
            // Feign 밖 예외(서비스 토큰 발급 실패 등) — 판정을 받지 못했다. 원인 메시지는 남기지 않는다
            log.warn("cancellation no-refund check call failed. claimId={}, orderId={}, paymentId={}, error={}", claim.id(), claim.orderId(), paymentId,
                exception.getClass().getSimpleName());
            throw new OrderException(OrderErrorCode.CANCELLATION_REJECTION_CHECK_UNAVAILABLE);
        }
        if (result == null) {
            log.error("cancellation no-refund check returned no result. claimId={}, orderId={}, paymentId={}", claim.id(), claim.orderId(), paymentId);
            throw new OrderException(OrderErrorCode.CANCELLATION_REJECTION_CHECK_UNAVAILABLE);
        }
        // switch 식이라 Outcome 에 값이 늘면 컴파일 오류다 — 확정이 아닌 새 결과가 조용히 거절을 통과하지 않는다(닫는 쪽)
        OrderErrorCode refused = switch (result.outcome()) {
            case NO_REFUND_CONFIRMED -> null;
            case REFUNDED -> {
                // order 는 결제사 거절(환불 실패)로 알고 있는데 payment 는 이 클레임의 환불을 확정했다 — 이미 갈라진 상태다. 대표가 환불을 확인하고 환불 완료로 확정한다
                log.warn("cancellation rejection refused, payment reports refund for a refund-failed claim. records diverged. claimId={}, orderId={}, "
                    + "paymentId={}, reason={}", claim.id(), claim.orderId(), paymentId, result.reasonCode());
                yield OrderErrorCode.CANCELLATION_REJECTION_REFUND_FOUND;
            }
            case UNCONFIRMED -> {
                log.warn("cancellation rejection refused, payment could not confirm no refund. claimId={}, orderId={}, paymentId={}, reason={}",
                    claim.id(), claim.orderId(), paymentId, result.reasonCode());
                yield OrderErrorCode.CANCELLATION_REJECTION_UNVERIFIED;
            }
            case UNAVAILABLE -> {
                log.warn("cancellation rejection refused, no-refund check unavailable. claimId={}, orderId={}, paymentId={}, reason={}", claim.id(),
                    claim.orderId(), paymentId, result.reasonCode());
                yield OrderErrorCode.CANCELLATION_REJECTION_CHECK_UNAVAILABLE;
            }
        };
        if (refused != null) {
            throw new OrderException(refused);
        }
        log.info("cancellation no-refund confirmed, rejection may proceed. claimId={}, orderId={}, paymentId={}, reason={}",
            claim.id(), claim.orderId(), paymentId, result.reasonCode());
    }
}
