package com.commercefoundry.domainlayer.settlement.domain.model;

/**
 * PG 정산 거래의 결제수단. PG 응답의 결제수단 문자열을 정산 어댑터가 이 값으로 바꾼다 — 모르는 값은 {@link #OTHER} 이고 원문은 저장하지 않는다.
 * 저장 · 합계용이며 대사 판정에 쓰지 않는다. payment 컨텍스트의 결제수단 enum 과 값이 같지만 컨텍스트 경계를 넘겨 쓰지 않으려고 따로 둔다
 * ({@code architecture-guide.md} §3 — domain 은 다른 컨텍스트 domain 을 필드로 갖지 않는다).
 */
public enum SettlementPaymentMethod {
    CARD,
    EASY_PAY,
    TRANSFER,
    VIRTUAL_ACCOUNT,
    MOBILE_PHONE,
    GIFT_CERTIFICATE,
    OTHER
}
