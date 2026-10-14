package com.commercefoundry.shared.commerce;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 판매 통화. 금액은 이 통화의 <b>최소 단위 정수</b>로 저장한다 ({@code coding-conventions.md} §9-3).
 *
 * <p>소수 자릿수를 서비스마다 따로 알면 USD 주문이 100배로 결제된다. PG 요청의 소수 변환은 반드시 이 값을 쓴다.
 * 통화를 추가하면 {@code CurrencyCodeTest} 가 ISO 4217 기본 자릿수와 맞는지 확인한다.
 */
@Getter
@RequiredArgsConstructor
public enum CurrencyCode {

    KRW(0),
    USD(2);

    /** 최소 단위까지의 소수 자릿수. KRW 0 (원), USD 2 (센트). */
    private final int fractionDigits;
}
