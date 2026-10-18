package com.commercefoundry.domainlayer.cart.application.model;

/**
 * 병합에서 항목을 담지 못하고 건너뛴 사유. 화면이 분기에만 쓰는 제어 코드라 metadata 가 아니라 문자열로 내린다 ({@code coding-conventions.md} §11 예외).
 * 판정 순서는 선언 순서다.
 */
public enum CartMergeSkipReason {

    /** product-service 에 없는 SKU. */
    SKU_NOT_FOUND,
    /** 품절 · 판매 종료 · 숨김. */
    NOT_PURCHASABLE,
    /** 맞춤 제작 상품이 아닌데 맞춤 제작 입력이 있다. */
    CUSTOM_OPTION_NOT_ALLOWED,
    /** 새 줄인데 장바구니가 이미 50줄이다. */
    LINE_LIMIT_EXCEEDED,
    /** 새 줄인데 상품의 1회 주문 최대 수량이 0 이라 한 개도 담을 수 없다. */
    QUANTITY_LIMIT_EXCEEDED
}
