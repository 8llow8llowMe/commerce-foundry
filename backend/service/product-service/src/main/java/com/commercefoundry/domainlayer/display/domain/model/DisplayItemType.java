package com.commercefoundry.domainlayer.display.domain.model;

/**
 * 전시 아이템 유형 — 화면이 분기에만 쓰는 제어 코드라 응답에 문자열로 내린다 ({@code coding-conventions.md} §11 예외).
 */
public enum DisplayItemType {

    /** 이미지 · 영상 한 개. */
    MEDIA,

    /** 상품 하나 — 고객 응답에서는 상품 목록과 같은 카드로 펼친다. */
    PRODUCT
}
