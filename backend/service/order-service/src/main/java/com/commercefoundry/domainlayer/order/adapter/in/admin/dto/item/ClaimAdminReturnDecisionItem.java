package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "반품 결정 미리보기 (#17). 접수(REQUESTED) 반품에만 있다. 승인 때 다시 계산하므로 표시용이다")
public record ClaimAdminReturnDecisionItem(
    @Schema(description = "지금 승인할 수 있는가 — reason 이 null 일 때만 true")
    boolean approvable,

    @Schema(description = "지금 거절할 수 있는가 — 거절은 반품 접수 + 주문 반품 요청만 본다. reason 이 NOT_DECIDABLE 일 때만 false")
    boolean rejectable,

    @Schema(description = "승인할 수 없는 이유 — NOT_DECIDABLE(반품 접수가 아님 · 주문이 반품 요청이 아님, 승인 · 거절 모두 불가) · RETURN_IN_FLIGHT(같은 주문의 "
        + "다른 반품 환불 진행 중, 거절은 가능) · NOT_CALCULABLE(주문 · 결제 기록 없음 · 금액 불일치 등 데이터 이상, 거절은 가능). 승인할 수 있으면 null", nullable = true)
    CodeNameDescriptionMetadata reason,

    @Schema(description = "항목 사유로 정한 기본 귀책 — CUSTOMER(고객) · SELLER(판매자). 운영자가 검수 뒤 바꿀 수 있다")
    CodeNameDescriptionMetadata defaultFault,

    @Schema(description = "귀책별 정책 금액(차감 전). 승인할 수 있을 때만 CUSTOMER · SELLER 순으로 있고, 아니면 빈 배열이다")
    List<RefundItem> refundByFault
) {
    @Schema(description = "귀책 하나의 정책 금액")
    public record RefundItem(
        @Schema(description = "귀책")
        CodeNameDescriptionMetadata fault,

        @Schema(description = "정책 금액(차감 전). 0 이하일 수 있다 — 무료배송 기준 아래로 내려가 다시 받는 배송비가 반품 상품 금액보다 크면 승인할 수 없다")
        MoneyItem amount
    ) {
    }
}
