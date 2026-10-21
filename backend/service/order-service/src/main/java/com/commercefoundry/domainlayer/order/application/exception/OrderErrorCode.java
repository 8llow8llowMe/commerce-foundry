package com.commercefoundry.domainlayer.order.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 주문 도메인 오류 코드 ({@code coding-conventions.md} §8). 번호 대역은 001 주문 조회(#16) · 002~009 주문 항목 검증 · 010 주문 금액 변경 ·
 * 011~015 배송비 견적 · 016~ 생성 · 멱등 · 재고 — 필드별 검증 코드(1xx)는 {@code OrderValidationMessage} 에 둔다.
 * 002~009 중 002 는 상품 서비스 장애(catalog {@code CATALOG_001} 번역, 503) 자리이고 009 는 비워 둔다.
 *
 * <p>주문 항목 검증 코드(003~008)는 {@code OrderQuoteProcessor} 가 판정 순서대로 처음 걸리는 하나만 낸다 ({@code order-flow-guide.md} §2-3).
 * 메시지의 {@code %s} 는 걸린 줄 모두를 고객이 알아볼 이름("상품명(옵션명)")으로 이은 것이다 — SKU 아이디 · product-service 메시지는 넣지 않는다.
 *
 * <p>배송비 견적 코드(011~015)는 delivery-service 의 업무 거절({@code DELIVERY_001/002/003/011})을 주문 계약으로 번역한 것이다 —
 * delivery 의 코드는 주문 응답에 그대로 나가지 않는다. 번역은 {@code ShippingFeeInternalResponseSupport} 가 한다.
 * 응답 봉투로 바꾸는 {@code OrderExceptionHandler} 는 주문 진입 API(주문서 미리보기, #13)와 함께 생긴다.
 */
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode {

    /**
     * product-service 서킷 오픈 · 전송 실패 · 5xx — catalog 의 {@code CATALOG_001} 을 {@code OrderExceptionHandler} 가 이 코드로 번역한다
     * ({@code architecture-guide.md} §3). SKU 응답이 계약과 다를 때(해외 주문의 SKU 무게가 1 미만)도 이 코드다 — 값을 추측하지 않는다.
     */
    PRODUCT_SERVICE_UNAVAILABLE("ORDER_002", "상품 정보를 잠시 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    /** 없는 SKU · 품절 · 판매 종료 · 숨김({@code purchasable=false}). {@code %s} 는 그 줄들의 이름이고, 없는 SKU 는 "찾을 수 없는 상품" 이다. */
    ITEM_NOT_PURCHASABLE("ORDER_003", "지금은 구매할 수 없는 상품이 있습니다: %s", HttpStatus.CONFLICT),
    /** 맞춤 제작 상품이 아닌데 맞춤 제작 입력이 있다. {@code %s} 는 그 줄들의 이름이다. */
    CUSTOM_OPTION_NOT_ALLOWED("ORDER_004", "맞춤 제작 상품이 아니어서 맞춤 제작 입력을 받을 수 없습니다: %s", HttpStatus.BAD_REQUEST),
    /**
     * 상품 단위 수량 합이 상품의 1회 주문 최대 수량을 넘는다 — 상품 컬럼이라 SKU 별로 세면 S 2 + M 2 로 "1인 2벌" 이 우회된다.
     * 장바구니는 줄 단위로만 막아 여기서 거절될 수 있다. {@code %s} 는 "상품명(최대 N개)" 목록이다.
     */
    QUANTITY_EXCEEDED("ORDER_005", "한 번에 주문할 수 있는 수량을 넘은 상품이 있습니다: %s", HttpStatus.CONFLICT),
    /** SKU 판매가의 통화가 섞였다 — 통화별 상품 가격(#20) 전에는 한 주문이 한 통화다. 환산하지 않는다. */
    CURRENCY_MIXED("ORDER_006", "통화가 다른 상품은 함께 주문할 수 없습니다.", HttpStatus.CONFLICT),
    /**
     * 해외 주문인데 무게 또는 HS 코드가 없는 SKU 가 있다 ({@code order-flow-guide.md} §2-2, #80). 배송할 수 없는 국가({@code ORDER_013})가 이 사유보다 앞선다.
     * {@code %s} 는 그 줄들의 이름이다.
     */
    INTERNATIONAL_SHIPPING_INFO_MISSING("ORDER_007", "해외 배송 정보가 준비되지 않아 해외로 주문할 수 없는 상품이 있습니다: %s", HttpStatus.CONFLICT),
    /** 같은 SKU · 같은 맞춤 제작 입력(정규화한 값)의 줄이 요청 안에 두 번 이상 있다 — 프론트가 한 줄로 합쳐 보낸다. {@code %s} 는 그 줄들의 이름이다. */
    DUPLICATE_LINE("ORDER_008", "같은 상품이 주문에 중복되어 있습니다: %s", HttpStatus.BAD_REQUEST),
    /**
     * delivery-service 서킷 오픈 · 전송 실패 · 타임아웃 · 5xx, 번역하지 않는 4xx(우리 요청 · 서비스 토큰 설정 문제), 응답 계약 위반
     * ({@code coding-conventions.md} §10). 같은 요청을 다시 보내면 풀릴 수 있다.
     */
    DELIVERY_SERVICE_UNAVAILABLE("ORDER_011", "배송비를 잠시 계산할 수 없습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    /** 배송비 정책이 없다({@code DELIVERY_001}) — 0원 배송으로 열리지 않게 주문을 막는다. 운영자가 정책을 등록해야 풀리므로 error 로그를 남긴다. */
    SHIPPING_UNAVAILABLE("ORDER_012", "지금은 이 배송지로 주문할 수 없습니다. 고객센터에 문의해 주세요.", HttpStatus.CONFLICT),
    /** 배송할 수 없는 국가({@code DELIVERY_002}) — 해외 배송이 꺼져 있거나 어느 해외 국가 그룹에도 없다. */
    DESTINATION_NOT_SUPPORTED("ORDER_013", "배송할 수 없는 국가입니다.", HttpStatus.BAD_REQUEST),
    /** 주문 통화가 그 배송 범위 정책의 통화와 다르다({@code DELIVERY_003}) — 환산하지 않는다. 예: 영문 스토어(USD) 주문 + 국내(KR) 배송지 (#70). */
    DESTINATION_CURRENCY_NOT_SUPPORTED("ORDER_014", "주문 통화로는 이 배송지에 배송할 수 없습니다.", HttpStatus.BAD_REQUEST),
    /**
     * 총 무게가 가장 큰 무게 구간을 넘는다({@code DELIVERY_011}). 총 무게가 int 범위를 넘어 견적을 부르지 않을 때도 같은 코드다
     * ({@code order-flow-guide.md} §2-2).
     */
    SHIPPING_WEIGHT_NOT_SUPPORTED("ORDER_015", "배송할 수 있는 무게를 넘었습니다. 주문 수량을 줄여 주세요.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
