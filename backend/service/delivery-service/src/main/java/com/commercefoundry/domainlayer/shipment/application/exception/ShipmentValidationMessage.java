package com.commercefoundry.domainlayer.shipment.application.exception;

/**
 * 송장 요청 검증 메시지 ({@code coding-conventions.md} §8-2). 접두어가 코드다 — 서비스 공용 {@code DeliveryExceptionHandler} 가 {@code ValidationErrorSupport} 로
 * 코드를 읽어 봉투를 만든다. 배송비 컨텍스트({@code DeliveryValidationMessage}, 101~139) 다음 번호를 이어 쓴다.
 */
public final class ShipmentValidationMessage {

    // 송장 일괄 등록 (#18). 모르는 택배사(carrier) 값 · 깨진 JSON 은 역직렬화 실패라 DELIVERY_100(어느 필드인지 밝힌다)이다
    public static final String ITEMS_SIZE = "DELIVERY_140:items 는 1개 이상 20개 이하여야 합니다.";
    public static final String ITEM_REQUIRED = "DELIVERY_141:items 에 null 원소를 넣을 수 없습니다.";
    public static final String ORDER_ID_FORMAT = "DELIVERY_142:orderId 는 1 이상의 숫자 문자열이어야 합니다.";
    public static final String ORDER_IDS_DUPLICATE = "DELIVERY_143:같은 주문(orderId)을 두 번 넣을 수 없습니다.";
    public static final String CARRIER_REQUIRED = "DELIVERY_144:carrier 는 필수입니다.";
    public static final String TRACKING_NUMBER_FORMAT = "DELIVERY_145:송장 번호(trackingNumber)는 영문 · 숫자 · 하이픈으로 1~50자여야 합니다.";

    private ShipmentValidationMessage() {
    }
}
