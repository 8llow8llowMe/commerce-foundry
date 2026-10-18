package com.commercefoundry.domainlayer.product.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 상품 도메인 오류 코드 ({@code coding-conventions.md} §8). 필드별 검증 코드(101~)는 {@link ProductValidationMessage} 에 둔다.
 *
 * <p>대역 — {@code 00x} 없는 리소스(404), {@code 02x} 재고(조정 · 선점 충돌과 부족, 409), {@code 1xx} 요청 검증(400).
 */
@Getter
@RequiredArgsConstructor
public enum ProductErrorCode {

    NOT_FOUND_PRODUCT("PRODUCT_001", "존재하지 않는 상품입니다.", HttpStatus.NOT_FOUND),
    NOT_FOUND_SKU("PRODUCT_002", "존재하지 않는 SKU 입니다.", HttpStatus.NOT_FOUND),

    /** 어드민 재고 조정의 기대 수량(CAS)이 현재 수량과 다르거나, 첫 재고 등록이 동시에 겹쳤다. 다시 읽고 조정한다. */
    STOCK_ADJUSTMENT_CONFLICT("PRODUCT_020", "재고가 그 사이 바뀌었습니다. 현재 재고를 다시 확인한 뒤 조정해 주세요.", HttpStatus.CONFLICT),

    /** 코드 접두어 없는 검증 메시지 · 역직렬화 실패의 폴백. */
    INVALID_REQUEST("PRODUCT_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_TYPE_INVALID("PRODUCT_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("PRODUCT_199", "필수 요청 파라미터가 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
