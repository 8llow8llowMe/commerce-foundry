package com.commercefoundry.domainlayer.order.application.info;

import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.ClaimRecoverySituation;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;

/**
 * 어드민 접수 상세. 멱등 키 · 결제 아이디 · 원시 실패 코드 · 시도 횟수는 전달하지 않는다 — 운영자에게는 업무 언어의 처리 상황만 보인다 (#17 A2b).
 *
 * @param recovery 결제 후 취소의 처리 상황 · 다음 자동 시도 시각 · 재시도 가능 여부 · 화면 버전
 */
public record ClaimAdminDetailInfo(
    long claimId, long orderId, Claim.Type type, Claim.Status status, Money expectedRefund, Instant requestedAt, List<Line> items, Recovery recovery
) {
    public static ClaimAdminDetailInfo from(Claim claim, boolean retryable) {
        return new ClaimAdminDetailInfo(claim.id(), claim.orderId(), claim.type(), claim.status(), claim.expectedRefund(), claim.requestedAt(),
            claim.items().stream().map(item -> new Line(item.orderItemId(), item.quantity(), item.reason())).toList(),
            new Recovery(ClaimRecoverySituation.of(claim), claim.nextAttemptAt(), retryable, claim.version()));
    }

    public record Line(long orderItemId, int quantity, ClaimItem.Reason reason) {
    }

    /**
     * @param nextAutomaticAttemptAt 자동 처리가 다시 확인할 시각. 대기 상태가 아니면 null
     * @param version 재시도 요청의 {@code expectedVersion} 으로 그대로 보내는 화면 버전
     */
    public record Recovery(ClaimRecoverySituation situation, Instant nextAutomaticAttemptAt, boolean retryable, long version) {
    }
}
