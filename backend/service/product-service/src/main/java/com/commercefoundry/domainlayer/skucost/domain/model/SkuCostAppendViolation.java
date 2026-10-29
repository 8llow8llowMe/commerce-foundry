package com.commercefoundry.domainlayer.skucost.domain.model;

/**
 * 새 원가 행을 타임라인 끝에 붙일 수 없는 이유. {@link SkuCostTimeline#validateAppend} 가 아래 순서로 하나만 돌려준다.
 */
public enum SkuCostAppendViolation {

    /** 적용 시작 시각이 지금 + 허용 오차보다 뒤다. */
    IN_FUTURE,
    /** 같은 SKU · 유형의 첫 행과 통화가 다르다. 타임라인의 통화는 첫 행으로 고정된다. */
    CURRENCY_MISMATCH,
    /** 적용 시작 시각이 같은 SKU · 유형의 최신 행보다 뒤가 아니다 (같거나 앞). */
    NOT_AFTER_LATEST
}
