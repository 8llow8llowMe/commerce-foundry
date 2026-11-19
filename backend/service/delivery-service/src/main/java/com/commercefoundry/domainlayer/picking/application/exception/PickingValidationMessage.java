package com.commercefoundry.domainlayer.picking.application.exception;

/**
 * 출고 묶음 요청 검증 메시지 ({@code coding-conventions.md} §8-2). 접두어가 코드다 — 서비스 공용 {@code DeliveryExceptionHandler} 가 {@code ValidationErrorSupport} 로
 * 코드를 읽어 봉투를 만든다. 송장 컨텍스트({@code ShipmentValidationMessage}, 140~149) 다음 번호를 이어 쓴다. 문구는 운영자가 읽는 업무 말이다.
 */
public final class PickingValidationMessage {

    public static final String ORDERS = "DELIVERY_150:출고 묶음에 담을 주문(orderIds)은 1건 이상 20건 이하여야 합니다.";
    public static final String ORDER_ID = "DELIVERY_151:주문 번호(orderId)는 1 이상의 숫자여야 합니다.";
    public static final String QUANTITIES = "DELIVERY_152:검수 수량(quantities)은 필수이며 상품 옵션은 100개까지 보낼 수 있습니다.";
    public static final String SKU_ID = "DELIVERY_153:상품 옵션 번호는 1 이상의 숫자여야 합니다.";
    public static final String QUANTITY = "DELIVERY_154:검수 수량은 1 이상의 정수여야 합니다.";
    public static final String DUPLICATE_ORDER_IDS = "DELIVERY_155:출고 묶음에 같은 주문을 두 번 담을 수 없습니다.";

    private PickingValidationMessage() {
    }
}
