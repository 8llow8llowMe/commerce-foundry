package com.commercefoundry.domainlayer.payment.application.mapper;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;

/**
 * 저장된 금액 · 통화 컬럼 쌍({@code xxx_amount} + {@code xxx_currency})을 {@link Money} 로 묶는 유일한 곳이다
 * ({@code coding-conventions.md} §9-3). 엔티티 매퍼가 여기를 거친다.
 *
 * <p>짝이 깨진 값(한쪽만 null)은 데이터 오류다. 조용히 null 이나 0 으로 넘기지 않고 {@link IllegalStateException} 으로 실패한다.
 *
 * <p>delivery-service 의 같은 이름 클래스를 그대로 옮겼다 — 공용 모듈로 올리는 것은 후속(#68)이다. 바꾸면 양쪽을 같이 바꾼다.
 */
public final class MoneyColumns {

    private MoneyColumns() {
    }

    /**
     * NOT NULL 금액 컬럼 쌍.
     *
     * @throws IllegalStateException 통화가 null 이거나 금액이 음수일 때
     */
    public static Money required(long amount, CurrencyCode currency) {
        if (currency == null) {
            throw new IllegalStateException("금액에 통화가 없습니다: amount=%d".formatted(amount));
        }
        return toMoney(amount, currency);
    }

    /**
     * 없을 수 있는 금액 컬럼 쌍. 금액과 통화는 함께 있거나 함께 없어야 한다.
     *
     * @return 둘 다 null 이면 null
     * @throws IllegalStateException 한쪽만 null 이거나 금액이 음수일 때
     */
    public static Money nullable(Long amount, CurrencyCode currency) {
        if (amount == null && currency == null) {
            return null;
        }
        if (amount == null || currency == null) {
            throw new IllegalStateException("금액과 통화는 함께 저장돼야 합니다: amount=%s, currency=%s".formatted(amount, currency));
        }
        return toMoney(amount, currency);
    }

    private static Money toMoney(long amount, CurrencyCode currency) {
        try {
            return Money.of(amount, currency);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("저장된 금액이 올바르지 않습니다: amount=%d, currency=%s".formatted(amount, currency), exception);
        }
    }
}
