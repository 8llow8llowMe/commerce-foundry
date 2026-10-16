package com.commercefoundry.domainlayer.product.application.model;

/**
 * 상품 목록 정렬. 모든 정렬은 마지막에 상품 아이디 내림차순으로 동률을 끊는다 — 페이지 경계에서 같은 상품이 두 번 나오거나 빠지지 않게.
 */
public enum ProductSortType {

    /** 최신 등록순 (Snowflake 아이디 내림차순). */
    NEWEST,

    /** 기본 판매가 낮은순. */
    PRICE_ASC,

    /** 기본 판매가 높은순. */
    PRICE_DESC
}
