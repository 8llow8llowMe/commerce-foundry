package com.commercefoundry.shared.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Nested
    @DisplayName("소수 표현 변환 — PG 요청·응답")
    class MajorUnit {

        @Test
        @DisplayName("USD 는 센트를 소수 2자리로 바꾼다 — 8950 → 89.50")
        void usdToMajorUnit() {
            assertThat(Money.of(8950, CurrencyCode.USD).toMajorUnit().toPlainString()).isEqualTo("89.50");
        }

        @Test
        @DisplayName("KRW 는 소수점이 없다 — 129000 → 129000")
        void krwToMajorUnit() {
            assertThat(Money.of(129000, CurrencyCode.KRW).toMajorUnit().toPlainString()).isEqualTo("129000");
        }

        @Test
        @DisplayName("PG 응답의 소수 표현을 최소 단위로 되돌린다 — 89.5 USD → 8950")
        void ofMajorUnitRestoresMinorUnits() {
            assertThat(Money.ofMajorUnit(new BigDecimal("89.5"), CurrencyCode.USD)).isEqualTo(Money.of(8950, CurrencyCode.USD));
            assertThat(Money.ofMajorUnit(new BigDecimal("129000.00"), CurrencyCode.KRW)).isEqualTo(Money.of(129000, CurrencyCode.KRW));
        }

        @Test
        @DisplayName("통화 자릿수를 넘는 값은 반올림하지 않고 실패한다 — 대사가 어긋나지 않게")
        void ofMajorUnitRejectsExtraDigits() {
            assertThatThrownBy(() -> Money.ofMajorUnit(new BigDecimal("89.505"), CurrencyCode.USD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("89.505");
            assertThatThrownBy(() -> Money.ofMajorUnit(new BigDecimal("1000.5"), CurrencyCode.KRW))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("변환을 왕복해도 금액이 그대로다")
        void roundTrip() {
            Money original = Money.of(1, CurrencyCode.USD);

            assertThat(Money.ofMajorUnit(original.toMajorUnit(), CurrencyCode.USD)).isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("비율 계산 — 최소 단위 미만은 버린다")
    class Rate {

        @Test
        @DisplayName("KRW 12,345원의 10% 할인액은 1,234원이다 (1,234.5 → 버림)")
        void krwRateRoundsDown() {
            assertThat(Money.of(12345, CurrencyCode.KRW).multiply(new BigDecimal("0.1"))).isEqualTo(Money.of(1234, CurrencyCode.KRW));
        }

        @Test
        @DisplayName("USD 9.99달러의 15% 는 1.49달러다 (149.85센트 → 버림)")
        void usdRateRoundsDown() {
            assertThat(Money.of(999, CurrencyCode.USD).multiply(new BigDecimal("0.15"))).isEqualTo(Money.of(149, CurrencyCode.USD));
        }

        @Test
        @DisplayName("할인액은 원가를 넘지 않는다 — 100% 할인은 원가 그대로")
        void fullRateKeepsAmount() {
            Money price = Money.of(129000, CurrencyCode.KRW);

            assertThat(price.minus(price.multiply(BigDecimal.ONE))).isEqualTo(Money.zero(CurrencyCode.KRW));
        }

        @Test
        @DisplayName("음수 비율은 거부한다")
        void negativeRateRejected() {
            assertThatThrownBy(() -> Money.of(1000, CurrencyCode.KRW).multiply(new BigDecimal("-0.1")))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("사칙 연산")
    class Arithmetic {

        @Test
        @DisplayName("같은 통화끼리 더하고 뺀다")
        void plusAndMinus() {
            Money price = Money.of(129000, CurrencyCode.KRW);
            Money shipping = Money.of(3000, CurrencyCode.KRW);

            assertThat(price.plus(shipping)).isEqualTo(Money.of(132000, CurrencyCode.KRW));
            assertThat(price.minus(shipping)).isEqualTo(Money.of(126000, CurrencyCode.KRW));
        }

        @Test
        @DisplayName("통화가 다르면 계산하지 않는다 — KRW + USD")
        void currencyMismatchRejected() {
            Money krw = Money.of(1000, CurrencyCode.KRW);
            Money usd = Money.of(1000, CurrencyCode.USD);

            assertThatThrownBy(() -> krw.plus(usd)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("KRW").hasMessageContaining("USD");
            assertThatThrownBy(() -> krw.minus(usd)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> krw.isGreaterThan(usd)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("결과가 음수면 실패한다 — 할인·환불이 원금을 넘는 계산 오류")
        void negativeResultRejected() {
            assertThatThrownBy(() -> Money.of(1000, CurrencyCode.KRW).minus(Money.of(1001, CurrencyCode.KRW)))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("수량을 곱하고, 넘치면 조용히 감기지 않고 실패한다")
        void timesAndOverflow() {
            assertThat(Money.of(129000, CurrencyCode.KRW).times(3)).isEqualTo(Money.of(387000, CurrencyCode.KRW));
            assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, CurrencyCode.KRW).times(2)).isInstanceOf(ArithmeticException.class);
        }

        @Test
        @DisplayName("음수 금액과 통화 없는 금액은 만들 수 없다")
        void invalidConstruction() {
            assertThatThrownBy(() -> Money.of(-1, CurrencyCode.KRW)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Money.of(1, null)).isInstanceOf(NullPointerException.class);
        }
    }
}
