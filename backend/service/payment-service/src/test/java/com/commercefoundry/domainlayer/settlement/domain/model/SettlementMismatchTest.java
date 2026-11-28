package com.commercefoundry.domainlayer.settlement.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementMismatchTest {

    private static final LocalDate SOLD_DATE = LocalDate.parse("2026-11-20");
    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");
    private static final String KEY = "tgen_settlement_mismatch_0001";

    @Test
    @DisplayName("처음 찾으면 OPEN — 해결 방법 · 시각이 없다")
    void openHasNoResolution() {
        SettlementMismatch mismatch = SettlementMismatch.open(1L, SOLD_DATE, amount(129_000, 100_000), CurrencyCode.KRW, NOW);

        assertThat(mismatch.status()).isEqualTo(SettlementMismatchStatus.OPEN);
        assertThat(mismatch.resolution()).isNull();
        assertThat(mismatch.resolvedAt()).isNull();
        assertThat(mismatch.expectedAmount()).isEqualTo(129_000);
        assertThat(mismatch.actualAmount()).isEqualTo(100_000);
        assertThat(mismatch.detectedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("OPEN → RESOLVED(AUTO_CLEARED) 는 허용 — 발견 시각 · 금액은 그대로 둔다")
    void openCanAutoClear() {
        SettlementMismatch cleared = SettlementMismatch.open(1L, SOLD_DATE, amount(129_000, 100_000), CurrencyCode.KRW, NOW).autoClear(NOW.plusSeconds(60));

        assertThat(cleared.status()).isEqualTo(SettlementMismatchStatus.RESOLVED);
        assertThat(cleared.resolution()).isEqualTo(SettlementMismatchResolution.AUTO_CLEARED);
        assertThat(cleared.resolvedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(cleared.detectedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("이미 닫힌 불일치는 자동 해소를 거절한다")
    void resolvedCannotAutoClear() {
        SettlementMismatch cleared = SettlementMismatch.open(1L, SOLD_DATE, amount(1, 2), CurrencyCode.KRW, NOW).autoClear(NOW);

        assertThatThrownBy(() -> cleared.autoClear(NOW)).isInstanceOf(SettlementTransitionException.class).hasMessageContaining("RESOLVED");
    }

    @Test
    @DisplayName("다시 찾으면 금액 · 발견 시각만 바뀌고, RESOLVED 였으면 OPEN 으로 되돌린다 — 처음 발견 매출일 · 아이디 · 버전은 그대로")
    void redetectReopens() {
        SettlementMismatch stored = new SettlementMismatch(7L, SOLD_DATE, KEY, 9L, SettlementMismatchType.AMOUNT, SettlementMismatchStatus.RESOLVED,
            SettlementMismatchResolution.AUTO_CLEARED, 1, 2, CurrencyCode.KRW, NOW, NOW, 3L);

        SettlementMismatch reopened = stored.redetect(amount(129_000, 90_000), NOW.plusSeconds(3_600));

        assertThat(reopened.status()).isEqualTo(SettlementMismatchStatus.OPEN);
        assertThat(reopened.resolution()).isNull();
        assertThat(reopened.resolvedAt()).isNull();
        assertThat(reopened.expectedAmount()).isEqualTo(129_000);
        assertThat(reopened.actualAmount()).isEqualTo(90_000);
        assertThat(reopened.detectedAt()).isEqualTo(NOW.plusSeconds(3_600));
        assertThat(reopened.soldDate()).isEqualTo(SOLD_DATE);
        assertThat(reopened.id()).isEqualTo(7L);
        assertThat(reopened.version()).isEqualTo(3L);
    }

    @Test
    @DisplayName("다른 결제키 · 종류의 판정으로는 다시 찾을 수 없다")
    void redetectRejectsOtherKeyOrType() {
        SettlementMismatch mismatch = SettlementMismatch.open(1L, SOLD_DATE, amount(1, 2), CurrencyCode.KRW, NOW);

        assertThatThrownBy(() -> mismatch.redetect(new SettlementDetection(KEY, 9L, SettlementMismatchType.CANCEL_TOTAL, 1, 2), NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mismatch.redetect(new SettlementDetection(KEY + "x", 9L, SettlementMismatchType.AMOUNT, 1, 2), NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결제 기록 누락에만 결제 아이디가 없다 · 상태와 해결 칸이 맞아야 만든다")
    void invariants() {
        assertThatThrownBy(() -> new SettlementDetection(KEY, null, SettlementMismatchType.AMOUNT, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementDetection(KEY, 9L, SettlementMismatchType.MISSING_IN_LEDGER, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementMismatch(1L, SOLD_DATE, KEY, 9L, SettlementMismatchType.AMOUNT, SettlementMismatchStatus.OPEN,
            SettlementMismatchResolution.AUTO_CLEARED, 0, 0, CurrencyCode.KRW, NOW, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementMismatch(1L, SOLD_DATE, KEY, 9L, SettlementMismatchType.AMOUNT, SettlementMismatchStatus.RESOLVED,
            null, 0, 0, CurrencyCode.KRW, NOW, NOW, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString 은 결제키를 가린다")
    void toStringMasksPaymentKey() {
        SettlementMismatch mismatch = SettlementMismatch.open(1L, SOLD_DATE, amount(1, 2), CurrencyCode.KRW, NOW);

        assertThat(mismatch.toString()).doesNotContain(KEY).contains("tgen_set***");
    }

    private static SettlementDetection amount(long expected, long actual) {
        return new SettlementDetection(KEY, 9L, SettlementMismatchType.AMOUNT, expected, actual);
    }
}
