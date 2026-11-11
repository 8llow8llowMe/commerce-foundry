package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "결제 후 취소 처리 상황 (#17). 원시 오류 코드 · 시도 횟수 · 결정자 아이디는 내지 않는다")
public record ClaimAdminRecoveryItem(
    @Schema(description = "처리 상황 — NO_ACTION · AUTO_PROCESSING · REFUND_UNCONFIRMED · REFUND_REJECTED · RESTORATION_UNCONFIRMED · "
        + "RESTORATION_REJECTED · ORDER_STATUS_MISMATCH. name · description 을 그대로 보여 준다")
    CodeNameDescriptionMetadata situation,

    @Schema(description = "자동 처리가 다시 확인할 시각 (UTC). 자동 처리 중이 아니면 null", nullable = true)
    Instant nextAutomaticAttemptAt,

    @Schema(description = "지금 '다시 시도' 를 할 수 있는가 (ORDER_MANAGE)")
    boolean retryable,

    @Schema(description = "지금 '환불 완료로 확정' 을 할 수 있는가 (SETTLEMENT_MANAGE — 대표만). 주문 상태는 실행 때 다시 본다")
    boolean refundConfirmable,

    @Schema(description = "지금 '재고 복원 없이 종결' 을 할 수 있는 접수인가 (SETTLEMENT_MANAGE — 대표만). 주문이 취소 완료인지는 실행 때 본다")
    boolean completable,

    @Schema(description = "마지막 운영자 결정(수동 환불 확정 · 복원 생략 종결). 없으면 null", nullable = true)
    DecisionItem lastDecision,

    @Schema(description = "화면 버전 — 다시 시도 · 결정 요청의 expectedVersion 으로 그대로 보낸다", example = "3")
    long version
) {
    @Schema(description = "운영자 결정 — 결정자 아이디는 내지 않는다(감사 로그 · 주문 이력에 남는다)")
    public record DecisionItem(
        @Schema(description = "판단 근거", example = "결제 관리 화면에서 취소 완료 확인")
        String reason,

        @Schema(description = "결정 시각 (UTC)", example = "2026-11-10T03:00:00Z")
        Instant decidedAt
    ) {
    }
}
