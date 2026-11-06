package com.commercefoundry.domainlayer.order.adapter.in.web.dto.response;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderAmountsItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderLineItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.ShippingAddressItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.ShippingFeeItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "내 주문 상세 — 주문 시점 스냅숏. 결제 · 배송 요약은 결제 연동(#14 · #308) · 배송(#18) 뒤에 더한다")
public record OrderDetailResponse(

    @Schema(description = "주문 아이디", example = "4611686018427387904")
    String orderId,

    @Schema(description = "주문 상태", example = "{\"code\":\"CANCELED\",\"name\":\"취소 완료\",\"description\":\"주문이 취소되었습니다.\"}")
    CodeNameDescriptionMetadata status,

    @Schema(description = "지금 취소를 요청할 수 있는가 — 결제 대기면 true, 결제 완료 · 상품 준비중(제작 중은 운영 설정)은 결제 후 취소가 열려 있을 때 true. 결제 뒤 취소는 환불을 거친다", example = "true")
    boolean cancelable,

    @Schema(description = "결제 기한 (UTC, ISO-8601)", example = "2026-10-18T09:30:00Z")
    Instant paymentDueAt,

    @Schema(description = "주문 통화 (ISO 4217)", example = "KRW")
    String currency,

    @Schema(description = "주문 항목 — 주문 시점 스냅숏, 주문 순서")
    List<OrderLineItem> items,

    @Schema(description = "금액")
    OrderAmountsItem amounts,

    @Schema(description = "배송비 내역 (주문 시점)")
    ShippingFeeItem shipping,

    @Schema(description = "배송지 (주문 시점) — 본인 주문이라 가리지 않는다")
    ShippingAddressItem shippingAddress,

    @Schema(description = "주문 시각 (UTC, ISO-8601)", example = "2026-10-18T09:00:00Z")
    Instant createdAt
) {

    @Override
    public String toString() {
        return "OrderDetailResponse[orderId=" + orderId + "]";
    }
}
