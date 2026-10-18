package com.commercefoundry.domainlayer.catalog.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 상품 카탈로그(product-service SKU 조회) 오류 코드 ({@code coding-conventions.md} §8). catalog 는 자기 API 가 없어 이 코드가 응답에 그대로 나가지 않는다 —
 * 호출한 컨텍스트의 {@code *ExceptionHandler} 가 자기 코드로 번역한다 (장바구니는 {@code CART_008}, {@code architecture-guide.md} §3).
 */
@Getter
@RequiredArgsConstructor
public enum CatalogErrorCode {

    /** product-service 서킷 오픈 · 전송 실패 · 오류 응답 · 계약 위반 ({@code coding-conventions.md} §10). */
    PRODUCT_SERVICE_UNAVAILABLE("CATALOG_001", "상품 정보를 잠시 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
