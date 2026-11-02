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

    // 배송 상태 변경 · 조회 (#18 PR-B2). 모르는 status 값 · 깨진 JSON 은 DELIVERY_100, 쿼리 파라미터 형식(숫자 · 상태 · true/false)은 DELIVERY_198 이다.
    // 쿼리의 orderId 양수 검증은 등록과 같은 DELIVERY_142, page 는 공용 DELIVERY_137 이다. 경로의 deliveryId 는 숫자가 아니면 DELIVERY_198, 0 이하는 없는 배송(404)이다
    public static final String STATUS_CHANGE_TARGET = "DELIVERY_146:status 는 IN_TRANSIT(배송 중) · DELIVERED(배송 완료) 중 하나여야 합니다.";
    public static final String REASON_LENGTH_MAX = "DELIVERY_147:사유(reason)는 200자 이하여야 합니다.";
    public static final String REASON_INVALID_CHARACTER = "DELIVERY_148:사유(reason)에는 줄바꿈 · 탭 같은 제어 문자나 깨진 문자를 쓸 수 없습니다.";
    public static final String PAGE_SIZE_RANGE = "DELIVERY_149:size 는 1 이상 100 이하여야 합니다.";

    private ShipmentValidationMessage() {
    }
}
