package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderAdminAmountsItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderAdminLineItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderAdminShippingAddressItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderStatusHistoryAdminItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * 어드민 주문 상세 (#17) — 항목 · 금액 · 배송지(마스킹) · 상태 이력 · 지금 바꿀 수 있는 다음 상태.
 */
@Builder
@Schema(description = "어드민 주문 상세")
public record OrderAdminResponse(

    @Schema(description = "주문 아이디", example = "4611686018427387904")
    String orderId,

    @Schema(description = "주문한 회원 아이디", example = "4611686018427387000")
    String memberId,

    @Schema(description = "주문 상태")
    CodeNameDescriptionMetadata status,

    @Schema(description = "이 화면에서 지금 바꿀 수 있는 다음 상태 — 비어 있으면 바꿀 수 있는 상태가 없다(결제 · 송장 · 클레임 같은 다른 흐름이 바꾼다)")
    List<CodeNameDescriptionMetadata> nextStatuses,

    @Schema(description = "주문 통화 (ISO 4217)", example = "KRW")
    String currency,

    @Schema(description = "주문 항목 — 주문 시점 스냅숏")
    List<OrderAdminLineItem> items,

    @Schema(description = "금액")
    OrderAdminAmountsItem amounts,

    @Schema(description = "배송지 (주문 시점) — 받는 분 · 연락처 · 상세 주소 · 요청 사항은 마스킹한다")
    OrderAdminShippingAddressItem shippingAddress,

    @Schema(description = "상태 이력 — 오래된 것부터")
    List<OrderStatusHistoryAdminItem> histories,

    @Schema(description = "결제 기한 (UTC, ISO-8601)", example = "2026-10-18T09:30:00Z")
    Instant paymentDueAt,

    @Schema(description = "주문 시각 (UTC, ISO-8601)", example = "2026-10-18T09:00:00Z")
    Instant createdAt
) {

    @Override
    public String toString() {
        return "OrderAdminResponse[orderId=" + orderId + ", status=" + (status == null ? null : status.code()) + "]";
    }
}
