package com.commercefoundry.domainlayer.shippingfee.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 배송 서비스 오류 코드 ({@code coding-conventions.md} §8). 필드별 검증 코드(101~)는 {@link DeliveryValidationMessage} 에 둔다.
 *
 * <p>서비스 공용이다 — 배송비(이 컨텍스트) 다음에 들어올 송장 · 배송 추적 컨텍스트도 {@code DELIVERY_} 대역을 이어서 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum DeliveryErrorCode {

    SHIPPING_POLICY_NOT_CONFIGURED("DELIVERY_001", "배송비 정책이 설정되지 않았습니다.", HttpStatus.CONFLICT),
    DESTINATION_NOT_SUPPORTED("DELIVERY_002", "배송할 수 없는 국가입니다.", HttpStatus.BAD_REQUEST),
    CURRENCY_MISMATCH("DELIVERY_003", "금액의 통화가 배송비 정책의 통화와 다릅니다.", HttpStatus.BAD_REQUEST),
    REMOTE_AREA_RANGE_INVALID("DELIVERY_004", "도서산간 구간의 시작 우편번호가 끝 우편번호보다 큽니다: %s", HttpStatus.BAD_REQUEST),
    REMOTE_AREA_RANGE_OVERLAP("DELIVERY_005", "도서산간 구간이 겹칩니다: %s, %s", HttpStatus.BAD_REQUEST),
    SHIPPING_POLICY_VERSION_CONFLICT("DELIVERY_006", "배송비 정책이 그 사이에 변경되었습니다. 다시 조회한 뒤 수정해 주세요.", HttpStatus.CONFLICT),

    /** 코드 접두어 없는 검증 메시지 · 역직렬화 실패(깨진 JSON, 없는 통화)의 폴백. */
    INVALID_REQUEST("DELIVERY_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_TYPE_INVALID("DELIVERY_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("DELIVERY_199", "필수 요청 파라미터가 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
