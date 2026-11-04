package com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "PG 승인일 기준 정상 승인 집계. 취소 뒤에도 최초 승인액을 유지한다")
public record PaymentApprovalSummaryResponse(
    @Schema(description = "집계 시작 시각 (UTC, 포함)")
    Instant from,

    @Schema(description = "집계 종료 시각 (UTC, 제외)")
    Instant to,

    @Schema(description = "통화 코드 · 결제수단 코드 오름차순. 일치하는 승인이 없으면 빈 배열")
    List<Group> groups
) {
    @Schema(description = "통화 · 결제수단별 정상 승인 사실. 통화는 grossApprovedAmount.currency에 있다")
    public record Group(
        @Schema(description = "결제수단 코드 · 이름 · 설명")
        CodeNameDescriptionMetadata method,

        @Schema(description = "승인된 결제 시도 건수")
        long approvalCount,

        @Schema(description = "최초 승인액 합계 — 최소 화폐 단위 정수와 통화")
        MoneyItem grossApprovedAmount
    ) {
    }
}
