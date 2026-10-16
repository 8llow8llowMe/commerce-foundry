package com.commercefoundry.domainlayer.product.domain.model;

/**
 * 맞춤 제작 입력 항목의 입력 유형.
 */
public enum CustomOrderInputType {

    /** 숫자 (치수 등). 최소 · 최대 · 증분을 쓴다. */
    NUMBER,

    /** 자유 입력. 최대값을 최대 글자 수로 쓴다. */
    TEXT
}
