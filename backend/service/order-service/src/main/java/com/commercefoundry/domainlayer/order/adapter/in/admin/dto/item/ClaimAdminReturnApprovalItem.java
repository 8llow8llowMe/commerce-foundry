package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "반품 승인 기록 (#17). 승인자 아이디는 내지 않는다(감사 로그에 남는다)")
public record ClaimAdminReturnApprovalItem(
    @Schema(description = "승인 때 운영자가 고른 귀책 — CUSTOMER(고객) · SELLER(판매자). 환불액 계산 근거다")
    CodeNameDescriptionMetadata fault,

    @Schema(description = "검수 뒤 재고로 되돌리는가")
    boolean restock,

    @Schema(description = "정책 금액에서 뺀 금액(반품 배송비 · 훼손 등). 환불액은 expectedRefund 다")
    MoneyItem deduction,

    @Schema(description = "승인 시각 (UTC)", example = "2026-11-10T03:00:00Z")
    Instant approvedAt
) {
}
