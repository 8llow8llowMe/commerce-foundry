package com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.shipment.application.command.ShipmentRegisterCommand;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentValidationMessage;
import com.commercefoundry.domainlayer.shipment.domain.model.CarrierCode;
import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 어드민 송장 일괄 등록 (#18) — 단건도 1건짜리 목록으로 보낸다. */
@Schema(description = "송장 일괄 등록")
public record ShipmentRegisterRequest(

    @Schema(description = "등록할 송장 (1~20건, 같은 주문은 한 번만) — 결과는 이 순서로 나온다")
    @NotNull(message = ShipmentValidationMessage.ITEMS_SIZE)
    @Size(min = 1, max = MAX_ITEMS, message = ShipmentValidationMessage.ITEMS_SIZE)
    List<@NotNull(message = ShipmentValidationMessage.ITEM_REQUIRED) @Valid Item> items
) {

    /**
     * 한 요청의 최대 건수 — 건마다 order-service 를 한 번씩 부르고, 게이트웨이 응답 제한(10초) 안에 끝나야 한다. 처리 마감(8초)을 넘긴 건은
     * {@code DELIVERY_022} 로 남기지만, 상한을 낮게 둬 정상 응답 시간에서는 마감에 닿지 않게 한다({@code api-design-guide.md} §2-19).
     */
    public static final int MAX_ITEMS = 20;

    private static final String ORDER_ID_PATTERN = "[1-9][0-9]{0,18}";

    /** 같은 주문을 두 번 넣지 않는다 — 두 번째가 첫 번째의 결과를 덮어 보이지 않게. */
    @Schema(hidden = true)
    @AssertTrue(message = ShipmentValidationMessage.ORDER_IDS_DUPLICATE)
    public boolean isOrderIdsDistinct() {
        if (items == null) {
            return true;
        }
        List<String> orderIds = items.stream().filter(Objects::nonNull).map(Item::orderId).filter(Objects::nonNull).toList();
        return new HashSet<>(orderIds).size() == orderIds.size();
    }

    public ShipmentRegisterCommand toCommand(long adminMemberId) {
        return new ShipmentRegisterCommand(items.stream().map(Item::toCommand).toList(), adminMemberId);
    }

    @Schema(description = "송장 한 건")
    public record Item(

        @Schema(description = "주문 아이디 (숫자 문자열) — 상품 준비중 주문이어야 출고된다", example = "4611686018427387904")
        @NotNull(message = ShipmentValidationMessage.ORDER_ID_FORMAT)
        @Pattern(regexp = ORDER_ID_PATTERN, message = ShipmentValidationMessage.ORDER_ID_FORMAT)
        String orderId,

        @Schema(description = "택배사 제어 코드 — 목록에 없는 택배사는 OTHER", example = "CJ_LOGISTICS")
        @NotNull(message = ShipmentValidationMessage.CARRIER_REQUIRED)
        CarrierCode carrier,

        @Schema(description = "송장 번호 (영문 · 숫자 · 하이픈 1~50자)", example = "123456789012")
        @NotNull(message = ShipmentValidationMessage.TRACKING_NUMBER_FORMAT)
        @Pattern(regexp = Shipment.TRACKING_NUMBER_PATTERN, message = ShipmentValidationMessage.TRACKING_NUMBER_FORMAT)
        String trackingNumber
    ) {

        /** 19자리 숫자가 long 범위를 넘으면 400 이다(파싱 오류가 500 으로 새지 않게). 형식이 틀린 값은 {@code @Pattern} 이 먼저 막는다. */
        @Schema(hidden = true)
        @AssertTrue(message = ShipmentValidationMessage.ORDER_ID_FORMAT)
        public boolean isOrderIdInRange() {
            if (orderId == null || !orderId.matches(ORDER_ID_PATTERN)) {
                return true;
            }
            try {
                Long.parseLong(orderId);
                return true;
            } catch (NumberFormatException exception) {
                return false;
            }
        }

        ShipmentRegisterCommand.Item toCommand() {
            return new ShipmentRegisterCommand.Item(Long.parseLong(orderId), carrier, trackingNumber);
        }
    }
}
