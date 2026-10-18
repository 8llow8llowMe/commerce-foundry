package com.commercefoundry.domainlayer.cart.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 장바구니 도메인 오류 코드 ({@code coding-conventions.md} §8). 필드별 검증 코드(101~)는 {@link CartValidationMessage} 에 둔다.
 */
@Getter
@RequiredArgsConstructor
public enum CartErrorCode {

    /** 없거나 다른 회원의 줄 — 존재를 숨기려고 둘 다 404 다 ({@code api-design-guide.md} §2-2). */
    CART_ITEM_NOT_FOUND("CART_001", "장바구니에 없는 상품입니다.", HttpStatus.NOT_FOUND),
    CART_SKU_NOT_FOUND("CART_002", "존재하지 않는 상품 옵션입니다.", HttpStatus.NOT_FOUND),
    /** 품절 · 판매 종료 · 숨김 상품은 새로 담을 수 없다. 이미 담긴 줄의 수량 변경 · 삭제는 막지 않는다. */
    CART_SKU_NOT_PURCHASABLE("CART_003", "지금은 구매할 수 없는 상품입니다.", HttpStatus.CONFLICT),
    CART_CUSTOM_OPTION_NOT_ALLOWED("CART_004", "맞춤 제작 상품이 아니어서 맞춤 제작 입력을 담을 수 없습니다.", HttpStatus.BAD_REQUEST),
    /** 줄 수량 상한 — 99개와 상품의 1회 주문 최대 수량 중 작은 값. {@code %s} 는 상한이다. */
    CART_QUANTITY_EXCEEDED("CART_005", "한 상품은 최대 %s개까지 담을 수 있습니다.", HttpStatus.CONFLICT),
    /** {@code %s} 는 줄 수 상한이다. */
    CART_ITEM_LIMIT_EXCEEDED("CART_006", "장바구니에는 최대 %s개 상품까지 담을 수 있습니다.", HttpStatus.CONFLICT),
    /** 같은 줄을 동시에 담았거나(유니크 키) 같은 줄을 동시에 고쳤다(@Version). 다시 시도하면 된다. */
    CART_CONCURRENTLY_MODIFIED("CART_007", "장바구니가 동시에 변경되었습니다. 다시 시도해 주세요.", HttpStatus.CONFLICT),
    /** product-service 서킷 오픈 · 전송 실패 · 5xx ({@code coding-conventions.md} §10). catalog 의 {@code CATALOG_001} 을 {@code CartExceptionHandler} 가 이 코드로 번역한다. */
    CART_PRODUCT_SERVICE_UNAVAILABLE("CART_008", "상품 정보를 잠시 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),

    /** 코드 접두어 없는 검증 메시지 · 역직렬화 실패의 폴백. */
    INVALID_REQUEST("CART_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_TYPE_INVALID("CART_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("CART_199", "필수 요청 파라미터가 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
