package com.commercefoundry.domainlayer.order.adapter.in.internal.dto.request;

import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/** 서비스 간 출고 반영 (#18) — delivery-service 가 송장을 등록한 뒤 주문을 배송 중으로 바꾼다. */
@Schema(description = "서비스 간 출고 반영")
public record OrderShipmentInternalRequest(

    @Schema(description = "delivery-service 송장(배송) 아이디 (숫자 문자열) — 추적 로그에 남긴다", example = "4611686018427387906")
    @NotNull(message = OrderValidationMessage.DELIVERY_ID_REQUIRED)
    @Pattern(regexp = InternalIdFormat.PATTERN, message = OrderValidationMessage.DELIVERY_ID_FORMAT)
    String deliveryId,

    @Schema(description = "출고 시각 (UTC, ISO-8601) — 주문에 출고 시각으로 남는다. 지금 기준 30일 전 ~ 5분 뒤", example = "2026-11-02T09:30:00Z")
    @NotNull(message = OrderValidationMessage.SHIPPED_AT_REQUIRED)
    Instant shippedAt
) {

    /** 19자리 숫자가 long 범위를 넘으면 400 이다(파싱 오류가 500 으로 새지 않게). 형식이 틀린 값은 {@code @Pattern} 이 먼저 막는다. */
    @Schema(hidden = true)
    @AssertTrue(message = OrderValidationMessage.DELIVERY_ID_FORMAT)
    public boolean isDeliveryIdInRange() {
        return InternalIdFormat.inRangeOrUnchecked(deliveryId);
    }

    public long deliveryIdValue() {
        return Long.parseLong(deliveryId);
    }
}
