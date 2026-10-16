package com.commercefoundry.domainlayer.product.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 상품 도메인 오류 코드 ({@code coding-conventions.md} §8). 필드별 검증 코드(101~)는 {@link ProductValidationMessage} 에 둔다.
 */
@Getter
@RequiredArgsConstructor
public enum ProductErrorCode {

    NOT_FOUND_PRODUCT("PRODUCT_001", "존재하지 않는 상품입니다.", HttpStatus.NOT_FOUND),

    /** 코드 접두어 없는 검증 메시지 · 역직렬화 실패의 폴백. */
    INVALID_REQUEST("PRODUCT_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_TYPE_INVALID("PRODUCT_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("PRODUCT_199", "필수 요청 파라미터가 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
