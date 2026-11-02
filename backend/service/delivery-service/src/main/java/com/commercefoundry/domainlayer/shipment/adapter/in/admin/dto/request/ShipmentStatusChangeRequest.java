package com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.shipment.application.command.ShipmentStatusChangeCommand;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentValidationMessage;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatusHistory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 어드민 배송 상태 변경 (#18 PR-B2). */
@Schema(description = "배송 상태 변경")
public record ShipmentStatusChangeRequest(

    @Schema(description = "바꿀 상태 — IN_TRANSIT(배송 중) · DELIVERED(배송 완료). 출고 완료(SHIPPED)는 송장 등록이 만든다", example = "DELIVERED",
        allowableValues = {"IN_TRANSIT", "DELIVERED"})
    @NotNull(message = ShipmentValidationMessage.STATUS_CHANGE_TARGET)
    ShipmentStatus status,

    @Schema(description = "사유 (200자 이하) — 배송 상태 이력에 남는다. 앞뒤 공백을 자르고, 공백뿐이면 없음(null)으로 남는다", nullable = true, example = "고객 수령 확인")
    @Size(max = ShipmentStatusHistory.REASON_MAX_LENGTH, message = ShipmentValidationMessage.REASON_LENGTH_MAX)
    @Pattern(regexp = "[^\\p{Cc}\\p{Cs}]*", message = ShipmentValidationMessage.REASON_INVALID_CHARACTER)
    String reason
) {

    /** 출고 완료는 바꿀 상태가 아니다 — 같은 코드(146)로 막는다. 없는 값은 {@code @NotNull} 이 먼저 막는다. */
    @Schema(hidden = true)
    @AssertTrue(message = ShipmentValidationMessage.STATUS_CHANGE_TARGET)
    public boolean isStatusChangeable() {
        return status != ShipmentStatus.SHIPPED;
    }

    public ShipmentStatusChangeCommand toCommand(long shipmentId, long adminMemberId) {
        return new ShipmentStatusChangeCommand(shipmentId, status, reason, adminMemberId);
    }
}
