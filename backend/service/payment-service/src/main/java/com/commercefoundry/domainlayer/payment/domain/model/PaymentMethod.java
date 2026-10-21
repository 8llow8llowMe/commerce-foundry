package com.commercefoundry.domainlayer.payment.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 승인된 결제수단. PG 응답의 결제수단 문자열을 PG 어댑터가 이 값으로 바꾼다 — 모르는 값은 {@link #OTHER} 로 두고 원문은 저장하지 않는다.
 * 간편결제 제공자(카카오페이 · 네이버페이 등)는 {@link Payment#easyPayProvider()} 에 따로 남긴다.
 */
@Getter
@RequiredArgsConstructor
public enum PaymentMethod implements CodeNameDescribable {

    CARD("카드"),
    EASY_PAY("간편결제"),
    TRANSFER("계좌이체"),
    VIRTUAL_ACCOUNT("가상계좌"),
    MOBILE_PHONE("휴대폰"),
    GIFT_CERTIFICATE("상품권"),
    OTHER("기타");

    private final String displayName;
}
