package com.commercefoundry.domainlayer.aiusage.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.aiusage.domain.model.AiPriceTable.AiPriceQuote;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AiUsageCallTest {

    private static final Instant CALLED_AT = Instant.parse("2026-10-29T01:00:00Z");
    private static final Instant SETTLED_AT = CALLED_AT.plusSeconds(3);
    private static final AiPrice PRICE = new AiPrice(new BigDecimal("1"), new BigDecimal("5"), new BigDecimal("1.25"), new BigDecimal("0.1"));
    private static final AiPriceQuote ESTIMATE_QUOTE = new AiPriceQuote(PRICE, AiPricedBy.MAX_FALLBACK, "v1");
    private static final AiPriceQuote ACTUAL_QUOTE = new AiPriceQuote(PRICE, AiPricedBy.CONFIG, "v2");
    private static final long ESTIMATE = 9_000L;
    private static final AiTokenUsage USAGE = new AiTokenUsage(100, 20, 0, 0);

    @ParameterizedTest
    @EnumSource(value = AiUsageStatus.class, names = {"COMPLETED", "UNUSABLE"})
    @DisplayName("응답을 받은 COMPLETED · UNUSABLE 은 답한 모델 단가로 실측 비용을 남긴다")
    void answeredOutcomesChargeActualUsage(AiUsageStatus outcome) {
        AiUsageCall closed = pending().settle(outcome, USAGE, "answered-model", ACTUAL_QUOTE, null, null, "msg_1", 1_200, SETTLED_AT);

        // 100×1 + 20×5
        assertThat(closed.costMicros()).isEqualTo(200L);
        assertThat(closed.usage()).isEqualTo(USAGE);
        assertThat(closed.model()).isEqualTo("answered-model");
        assertThat(closed.pricedBy()).isEqualTo(AiPricedBy.CONFIG);
        assertThat(closed.pricingVersion()).isEqualTo("v2");
        assertThat(closed.status()).isEqualTo(outcome);
        assertThat(closed.settledAt()).isEqualTo(SETTLED_AT);
        assertThat(closed.elapsedMs()).isEqualTo(1_200);
    }

    @ParameterizedTest
    @EnumSource(value = AiUsageStatus.class, names = {"FAILED", "NOT_SENT"})
    @DisplayName("오류 응답 FAILED · 보내지 않은 NOT_SENT 는 비용 0 — 사용량이 와도 무시하고 0 이다")
    void unsentOrErrorOutcomesCostNothing(AiUsageStatus outcome) {
        AiUsageCall closed = pending().settle(outcome, USAGE, null, null, "RATE_LIMITED", 429, null, 0, SETTLED_AT);

        assertThat(closed.costMicros()).isZero();
        assertThat(closed.usage()).isEqualTo(AiTokenUsage.ZERO);
        assertThat(closed.model()).as("답한 모델이 없으면 요청한 모델").isEqualTo("requested-model");
        assertThat(closed.pricedBy()).as("예상치의 단가 출처를 남긴다").isEqualTo(AiPricedBy.MAX_FALLBACK);
        assertThat(closed.httpStatus()).isEqualTo(429);
        assertThat(closed.reason()).isEqualTo("RATE_LIMITED");
    }

    @Test
    @DisplayName("TIMEOUT 은 과금됐는지 몰라 예약한 예상치를 1회 비용으로 남긴다")
    void timeoutKeepsEstimate() {
        AiUsageCall closed = pending().settle(AiUsageStatus.TIMEOUT, null, null, null, "UNAVAILABLE", null, null, 60_000, SETTLED_AT);

        assertThat(closed.costMicros()).isEqualTo(ESTIMATE);
        assertThat(closed.usage()).isEqualTo(AiTokenUsage.ZERO);
    }

    @Test
    @DisplayName("회수(ABANDONED)도 예상치를 비용으로 남긴다")
    void abandonKeepsEstimate() {
        AiUsageCall abandoned = pending().abandon(SETTLED_AT);

        assertThat(abandoned.status()).isEqualTo(AiUsageStatus.ABANDONED);
        assertThat(abandoned.costMicros()).isEqualTo(ESTIMATE);
        assertThat(abandoned.settledAt()).isEqualTo(SETTLED_AT);
    }

    @Test
    @DisplayName("닫힌 호출은 다시 닫지 않는다 — PENDING · ABANDONED 는 호출 결과로 받지 않는다")
    void closedCallCannotBeClosedAgain() {
        AiUsageCall closed = pending().settle(AiUsageStatus.NOT_SENT, null, null, null, "BUSY", null, null, 0, SETTLED_AT);

        assertThatThrownBy(() -> closed.settle(AiUsageStatus.COMPLETED, USAGE, null, ACTUAL_QUOTE, null, null, null, 0, SETTLED_AT))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> closed.abandon(SETTLED_AT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pending().settle(AiUsageStatus.PENDING, null, null, null, null, null, null, 0, SETTLED_AT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending().settle(AiUsageStatus.ABANDONED, null, null, null, null, null, null, 0, SETTLED_AT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending().settle(AiUsageStatus.COMPLETED, null, null, ACTUAL_QUOTE, null, null, null, 0, SETTLED_AT))
            .as("응답을 받은 결과에는 사용량이 있어야 한다")
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("호출 수에는 보내지 않은 NOT_SENT 만 빠진다")
    void callCountRule() {
        assertThat(AiUsageStatus.NOT_SENT.countsAsCall()).isFalse();
        assertThat(AiUsageStatus.PENDING.countsAsCall()).isFalse();
        assertThat(AiUsageStatus.FAILED.countsAsCall()).isTrue();
        assertThat(AiUsageStatus.TIMEOUT.countsAsCall()).isTrue();
        assertThat(AiUsageStatus.ABANDONED.countsAsCall()).isTrue();
        assertThat(AiUsageStatus.COMPLETED.countsAsCall()).isTrue();
    }

    @Test
    @DisplayName("정산은 길이로 실패하지 않는다 — 답한 모델 · 메시지 ID 는 64자, 원인은 32자로 자르고 걸린 시간은 int 범위로 자른다")
    void clampsLongValues() {
        AiUsageCall closed = pending().settle(
            AiUsageStatus.COMPLETED, USAGE, "x".repeat(100), ACTUAL_QUOTE, "r".repeat(40), null, "m".repeat(80), Long.MAX_VALUE, SETTLED_AT
        );

        assertThat(closed.model()).hasSize(64);
        assertThat(closed.reason()).hasSize(32);
        assertThat(closed.messageId()).hasSize(64);
        assertThat(closed.elapsedMs()).isEqualTo(Integer.MAX_VALUE);
    }

    private static AiUsageCall pending() {
        return AiUsageCall.pending(
            1L, new AiBudgetPeriod("2026-10"), "size-chart", 1, "job:1", "requested-model", true, 0, 1_000, ESTIMATE, ESTIMATE_QUOTE, CALLED_AT
        );
    }
}
