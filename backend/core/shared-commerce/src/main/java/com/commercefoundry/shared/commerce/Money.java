package com.commercefoundry.shared.commerce;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 금액 값 객체 — 최소 화폐 단위 정수 + 통화 ({@code coding-conventions.md} §9-3, {@code api-design-guide.md} §2-3).
 *
 * <p><b>반올림 규칙은 이 클래스에만 둔다.</b> 서비스가 {@code BigDecimal} 로 따로 계산하면 order 와 payment 의 금액이
 * 1원·1센트씩 어긋나고, 그 차이가 결제 금액 검증 실패나 환불 한도 초과로 돌아온다.
 *
 * <ul>
 *   <li>비율 계산(할인액·수수료)은 최소 단위 미만을 <b>버린다</b>({@link #ROUNDING}). 할인액이 원가를 넘지 않는다.</li>
 *   <li>소수 표현 변환({@link #ofMajorUnit})은 <b>반올림하지 않는다</b>. 자릿수가 넘치면 실패한다 — PG 응답 금액을
 *       조용히 깎으면 대사가 어긋난다.</li>
 * </ul>
 *
 * <p>음수 금액은 만들지 않는다. 할인·환불은 양수 금액으로 들고 {@link #minus} 로 뺀다.
 */
public record Money(long amount, CurrencyCode currency) {

    public static final RoundingMode ROUNDING = RoundingMode.DOWN;

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (amount < 0) {
            throw new IllegalArgumentException("금액은 음수일 수 없습니다: %d %s".formatted(amount, currency));
        }
    }

    public static Money of(long amount, CurrencyCode currency) {
        return new Money(amount, currency);
    }

    public static Money zero(CurrencyCode currency) {
        return new Money(0L, currency);
    }

    /**
     * 소수 표현(PG 응답의 {@code 89.50})을 최소 단위로 바꾼다.
     *
     * @throws IllegalArgumentException 통화의 소수 자릿수를 넘는 값({@code 89.505 USD}, {@code 1000.5 KRW})
     */
    public static Money ofMajorUnit(BigDecimal value, CurrencyCode currency) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(currency, "currency");
        try {
            BigDecimal minorUnits = value.setScale(currency.getFractionDigits(), RoundingMode.UNNECESSARY).movePointRight(currency.getFractionDigits());
            return new Money(minorUnits.longValueExact(), currency);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("%s 는 소수 %d자리까지만 표현할 수 있습니다: %s"
                .formatted(currency, currency.getFractionDigits(), value.toPlainString()), exception);
        }
    }

    /** PG 요청용 소수 표현. USD {@code 8950} → {@code 89.50}, KRW {@code 129000} → {@code 129000}. */
    public BigDecimal toMajorUnit() {
        return BigDecimal.valueOf(amount, currency.getFractionDigits());
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amount, other.amount), currency);
    }

    /** @throws IllegalArgumentException 결과가 음수일 때 — 할인·환불이 원금을 넘는 계산 오류다 */
    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amount, other.amount), currency);
    }

    public Money times(long quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("수량은 음수일 수 없습니다: " + quantity);
        }
        return new Money(Math.multiplyExact(amount, quantity), currency);
    }

    /**
     * 비율을 곱한다 — 할인율 {@code 0.15}, 수수료율 {@code 0.033} 등. 최소 단위 미만은 버린다({@link #ROUNDING}).
     *
     * @param rate 0 이상. 문자열로 만든 {@code BigDecimal} 을 넘긴다 ({@code new BigDecimal("0.1")}) — double 생성자는 오차를 가져온다
     */
    public Money multiply(BigDecimal rate) {
        Objects.requireNonNull(rate, "rate");
        if (rate.signum() < 0) {
            throw new IllegalArgumentException("비율은 음수일 수 없습니다: " + rate.toPlainString());
        }
        BigDecimal result = BigDecimal.valueOf(amount).multiply(rate).setScale(0, ROUNDING);
        return new Money(result.longValueExact(), currency);
    }

    public boolean isZero() {
        return amount == 0L;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount > other.amount;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (currency != other.currency) {
            throw new IllegalArgumentException("통화가 다른 금액은 계산할 수 없습니다: %s, %s".formatted(currency, other.currency));
        }
    }
}
