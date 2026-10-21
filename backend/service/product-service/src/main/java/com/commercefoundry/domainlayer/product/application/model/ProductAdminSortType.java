package com.commercefoundry.domainlayer.product.application.model;

/**
 * 어드민 상품 목록 정렬. 고객 목록({@link ProductSortType})과 나눈다 — 어드민 표에만 이름순이 있고, 고객 목록에 이름순을 열지 않는다.
 * 모든 정렬은 마지막에 상품 아이디 내림차순으로 동률을 끊는다 — 페이지 경계에서 같은 상품이 두 번 나오거나 빠지지 않게.
 */
public enum ProductAdminSortType {

    /** 최신 등록순 (Snowflake 아이디 내림차순). */
    NEWEST,

    /** 상품명 오름차순 — DB 정렬 규칙(collation)을 따른다. */
    NAME_ASC
}
