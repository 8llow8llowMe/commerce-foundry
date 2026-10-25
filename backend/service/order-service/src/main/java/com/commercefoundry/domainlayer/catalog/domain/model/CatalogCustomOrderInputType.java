package com.commercefoundry.domainlayer.catalog.domain.model;

/**
 * product-service 맞춤 제작 항목의 입력 유형. 원격 응답의 문자열을 옮긴 값이다.
 */
public enum CatalogCustomOrderInputType {

    /** 숫자 (치수 등). 최소 · 최대 · 증분을 쓴다. */
    NUMBER,

    /** 자유 입력. 최대값을 최대 글자 수로 쓴다. */
    TEXT
}
