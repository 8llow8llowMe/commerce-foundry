package com.commercefoundry.domainlayer.order.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 주문 도메인 오류 코드 ({@code coding-conventions.md} §8). 번호 대역은 001 주문 조회(#16) · 002~009 주문 항목 검증 · 010 주문 금액 변경 ·
 * 011~015 배송비 견적 · 016~ 생성 · 멱등 · 재고 — 필드별 검증 코드(1xx)는 {@code OrderValidationMessage} 에 둔다.
 *
 * <p>배송비 견적 코드(011~015)는 delivery-service 의 업무 거절({@code DELIVERY_001/002/003/011})을 주문 계약으로 번역한 것이다 —
 * delivery 의 코드는 주문 응답에 그대로 나가지 않는다. 번역은 {@code ShippingFeeInternalResponseSupport} 가 한다.
 * 응답 봉투로 바꾸는 {@code OrderExceptionHandler} 는 주문 진입 API(주문서 미리보기, #13)와 함께 생긴다.
 */
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode {

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
