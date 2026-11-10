package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "결제 후 취소 처리 상황 (#17). 원시 오류 코드 · 시도 횟수는 내지 않는다")
public record ClaimAdminRecoveryItem(
    @Schema(description = "처리 상황 — NO_ACTION · AUTO_PROCESSING · REFUND_UNCONFIRMED · REFUND_REJECTED · RESTORATION_UNCONFIRMED · "
        + "RESTORATION_REJECTED · ORDER_STATUS_MISMATCH. name · description 을 그대로 보여 준다")
    CodeNameDescriptionMetadata situation,

    @Schema(description = "자동 처리가 다시 확인할 시각 (UTC). 자동 처리 중이 아니면 null", nullable = true)
    Instant nextAutomaticAttemptAt,

    @Schema(description = "지금 '다시 시도' 를 할 수 있는가")
    boolean retryable,

    @Schema(description = "화면 버전 — 다시 시도 요청의 expectedVersion 으로 그대로 보낸다", example = "3")
    long version
) {
}
