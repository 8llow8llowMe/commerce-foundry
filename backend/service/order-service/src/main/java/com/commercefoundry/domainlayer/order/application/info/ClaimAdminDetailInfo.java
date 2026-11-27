package com.commercefoundry.domainlayer.order.application.info;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.ClaimRecoverySituation;
import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 어드민 접수 상세. 멱등 키 · 결제 아이디 · 원시 실패 코드 · 시도 횟수 · 결정자 아이디는 전달하지 않는다 — 운영자에게는 업무 언어의 처리 상황만 보인다 (#17).
 *
 * @param recovery 결제 후 취소의 처리 상황 · 다음 자동 시도 시각 · 처리 버튼 표시 여부 · 마지막 운영자 결정 · 화면 버전
 * @param returnApproval 승인된 반품의 승인 기록(귀책 · 재고 되돌림 · 차감액 · 승인 시각, #17 B2c). 승인 전 · 거절 반품과 취소는 null
 * @param returnDecision 반품 결정 미리보기(#17 B2c) — 결정 대기 반품({@link Claim#isAwaitingReturnDecision()})에만 있고 나머지는 null
 */
public record ClaimAdminDetailInfo(
    long claimId, long orderId, Claim.Type type, Claim.Status status, Money expectedRefund, Instant requestedAt, List<Line> items, Recovery recovery,
    ReturnApproval returnApproval, ReturnDecision returnDecision
) {
    /**
     * @param retryable 다시 시도 선점 조건(A2b)
     * @param refundConfirmable 수동 환불 확정 조건(A3, 주문 상태는 실행 때 본다)
     * @param completable 복원 생략 종결 조건(A3, 주문 상태는 실행 때 본다) — 세 플래그 모두 {@code ClaimAdminRecoveryProcessor} 가 실행과 같은 조건으로 판정한다
     * @param returnDecision 반품 결정 미리보기. 결정 대기 반품이 아니면 null 을 넘긴다
     */
    public static ClaimAdminDetailInfo from(Claim claim, boolean retryable, boolean refundConfirmable, boolean completable, ReturnDecision returnDecision) {
        Claim.Decision decision = claim.decision();
        Claim.ReturnApproval approval = claim.approval();
        return new ClaimAdminDetailInfo(claim.id(), claim.orderId(), claim.type(), claim.status(), claim.expectedRefund(), claim.requestedAt(),
            claim.items().stream().map(item -> new Line(item.orderItemId(), item.quantity(), item.reason())).toList(),
            new Recovery(ClaimRecoverySituation.of(claim), claim.nextAttemptAt(), retryable, refundConfirmable, completable,
                decision == null ? null : new LastDecision(decision.reason(), decision.decidedAt()), claim.version()),
            approval == null ? null : new ReturnApproval(approval.fault(), approval.restock(), approval.deduction(), approval.approvedAt()), returnDecision);
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

    /** 승인된 반품의 승인 기록 — 승인자 아이디는 내지 않는다(감사 로그에 남는다). */
    public record ReturnApproval(ReturnRefundPolicy.Fault fault, boolean restock, Money deduction, Instant approvedAt) {
    }

    /**
     * 반품 결정 미리보기 (#17 B2c) — 지금 승인 · 거절할 수 있는지와 귀책별 정책 금액(차감 전). 승인 때 다시 계산하므로 화면 표시용이다.
     * 거절은 반품 접수 + 주문 반품 요청만 보고, 진행 중 반품 · 결제 대조 · 산식은 승인에만 걸린다 — 그래서 승인 가능({@link #approvable()})과
     * 거절 가능({@link #rejectable()})이 따로다.
     *
     * @param blocker 승인할 수 없는 이유. 승인할 수 있으면 null. {@link Blocker#NOT_DECIDABLE} 이 아니면 거절은 할 수 있다
     * @param defaultFault 항목 사유로 정한 기본 귀책({@link ReturnRefundPolicy.Fault#defaultOf}). 반품이 아니면(결정 대상 아님) null
     * @param refundByFault 귀책별 정책 금액 — 승인할 수 있을 때만 귀책 선언 순서로 채우고, 아니면 비어 있다
     */
    public record ReturnDecision(Blocker blocker, ReturnRefundPolicy.Fault defaultFault, List<FaultRefund> refundByFault) {

        public ReturnDecision {
            refundByFault = List.copyOf(refundByFault);
            if ((blocker == null) == refundByFault.isEmpty() || (defaultFault == null && blocker != Blocker.NOT_DECIDABLE)) {
                throw new IllegalArgumentException("승인할 수 있을 때만 귀책별 금액이 있고, 기본 귀책은 결정 대상 아님에서만 비울 수 있습니다");
            }
        }

        public static ReturnDecision approvable(ReturnRefundPolicy.Fault defaultFault, List<FaultRefund> refundByFault) {
            return new ReturnDecision(null, Objects.requireNonNull(defaultFault, "defaultFault"), refundByFault);
        }

        public static ReturnDecision blocked(Blocker blocker, ReturnRefundPolicy.Fault defaultFault) {
            return new ReturnDecision(Objects.requireNonNull(blocker, "blocker"), defaultFault, List.of());
        }

        /** 지금 승인할 수 있는가 — 막는 이유가 없다. */
        public boolean approvable() {
            return blocker == null;
        }

        /** 지금 거절할 수 있는가 — 결정 대상(반품 접수 + 주문 반품 요청)이면 진행 중 반품 · 계산 불가와 무관하게 거절할 수 있다. */
        public boolean rejectable() {
            return blocker != Blocker.NOT_DECIDABLE;
        }
    }

    /**
     * 귀책 하나의 정책 금액(최소 단위, 차감 전). 음수일 수 있다 — 재부과액이 반품 상품 금액보다 크면 승인할 수 없다(환불액 0 이하 거부).
     */
    public record FaultRefund(ReturnRefundPolicy.Fault fault, long policyAmount, CurrencyCode currency) {

        public FaultRefund {
            Objects.requireNonNull(fault, "fault");
            Objects.requireNonNull(currency, "currency");
        }
    }

    /**
     * 반품을 지금 승인할 수 없는 이유 — 이름(코드)이 API 값이다. 승인 API(B2c-2)의 409 오류 코드와 짝을 이룬다. {@link #NOT_DECIDABLE} 만 거절도 막는다.
     */
    @Getter
    @RequiredArgsConstructor
    public enum Blocker implements CodeNameDescribable {
        NOT_DECIDABLE("결정 대상 아님", "반품 접수 상태가 아니거나(그 사이 승인 · 거절됨 포함) 주문이 반품 요청 상태가 아닙니다. 승인 · 거절 모두 할 수 없습니다."),
        RETURN_IN_FLIGHT("다른 반품 환불 진행 중", "같은 주문의 다른 반품 환불이 끝나지 않았습니다. 그 반품을 먼저 마무리해야 승인할 수 있습니다. 거절은 할 수 있습니다."),
        NOT_CALCULABLE("환불액 계산 불가", "주문이나 결제 기록이 없거나 결제 금액이 주문 금액과 맞지 않아 환불액을 계산할 수 없습니다. 주문 · 결제 기록 확인이 필요합니다.");

        private final String displayName;
        private final String description;
    }
}
