package com.commercefoundry.domainlayer.order.application.info;

import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.ClaimRecoverySituation;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;

/**
 * 어드민 접수 상세. 멱등 키 · 결제 아이디 · 원시 실패 코드 · 시도 횟수 · 결정자 아이디는 전달하지 않는다 — 운영자에게는 업무 언어의 처리 상황만 보인다 (#17).
 *
 * @param recovery 결제 후 취소의 처리 상황 · 다음 자동 시도 시각 · 처리 버튼 표시 여부 · 마지막 운영자 결정 · 화면 버전
 */
public record ClaimAdminDetailInfo(
    long claimId, long orderId, Claim.Type type, Claim.Status status, Money expectedRefund, Instant requestedAt, List<Line> items, Recovery recovery
) {
    /**
     * @param retryable 다시 시도 선점 조건(A2b)
     * @param refundConfirmable 수동 환불 확정 조건(A3, 주문 상태는 실행 때 본다)
     * @param completable 복원 생략 종결 조건(A3, 주문 상태는 실행 때 본다) — 세 플래그 모두 {@code ClaimAdminRecoveryProcessor} 가 실행과 같은 조건으로 판정한다
     */
    public static ClaimAdminDetailInfo from(Claim claim, boolean retryable, boolean refundConfirmable, boolean completable) {
        Claim.Decision decision = claim.decision();
        return new ClaimAdminDetailInfo(claim.id(), claim.orderId(), claim.type(), claim.status(), claim.expectedRefund(), claim.requestedAt(),
            claim.items().stream().map(item -> new Line(item.orderItemId(), item.quantity(), item.reason())).toList(),
            new Recovery(ClaimRecoverySituation.of(claim), claim.nextAttemptAt(), retryable, refundConfirmable, completable,
                decision == null ? null : new LastDecision(decision.reason(), decision.decidedAt()), claim.version()));
    }

    public record Line(long orderItemId, int quantity, ClaimItem.Reason reason) {
    }

    /**
     * @param nextAutomaticAttemptAt 자동 처리가 다시 확인할 시각. 대기 상태가 아니면 null
     * @param completable 복원 생략 종결 후보(A3) — 주문이 취소 완료인지는 실행 때 본다
     * @param lastDecision 마지막 운영자 결정(수동 환불 확정 · 복원 생략 종결). 없으면 null
     * @param version 다시 시도 · 결정 요청의 {@code expectedVersion} 으로 그대로 보내는 화면 버전
     */
    public record Recovery(
        ClaimRecoverySituation situation, Instant nextAutomaticAttemptAt, boolean retryable, boolean refundConfirmable, boolean completable,
        LastDecision lastDecision, long version
    ) {
    }

    public record LastDecision(String reason, Instant decidedAt) {
    }
}
