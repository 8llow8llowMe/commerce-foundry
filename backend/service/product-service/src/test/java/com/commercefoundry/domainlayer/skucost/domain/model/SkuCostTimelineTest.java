package com.commercefoundry.domainlayer.skucost.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 원가 타임라인 — 시점 적용 원가는 유형별 "시작이 그 시각 이하인 가장 늦은 행" 이고, 새 행은 미래 한도 · 통화 고정 · 단조를 모두 지켜야 붙는다.
 */
class SkuCostTimelineTest {

    private static final Instant NOW = Instant.parse("2026-10-28T09:00:00Z");

    @Nested
    @DisplayName("resolveAt — 시점 적용 원가")
    class ResolveAt {

        @Test
        @DisplayName("유형마다 시작이 그 시각 이하인 가장 늦은 행을 고르고, 유형 선언 순서(GOODS → PACKAGING)로 낸다")
        void picksLatestStartedRowPerType() {
            List<SkuCost> rows = List.of(
                cost(3L, SkuCostType.PACKAGING, 500, "2026-10-01T00:00:00Z"),
                cost(1L, SkuCostType.GOODS, 10_000, "2026-09-01T00:00:00Z"),
                cost(2L, SkuCostType.GOODS, 12_000, "2026-10-01T00:00:00Z")
            );

            assertThat(SkuCostTimeline.resolveAt(rows, NOW)).extracting(SkuCost::id).containsExactly(2L, 3L);
        }

        @Test
        @DisplayName("시작이 기준 시각과 같으면 적용한다 (포함)")
        void startIsInclusive() {
            List<SkuCost> rows = List.of(cost(1L, SkuCostType.GOODS, 10_000, "2026-09-01T00:00:00Z"), cost(2L, SkuCostType.GOODS, 12_000, NOW.toString()));

            assertThat(SkuCostTimeline.resolveAt(rows, NOW)).extracting(SkuCost::id).containsExactly(2L);
        }

        @Test
        @DisplayName("아직 시작하지 않은 행은 고르지 않는다 — 그 유형에 시작한 행이 없으면 결과에서 빠진다 (0 으로 채우지 않는다)")
        void futureRowsAreSkippedAndMissingTypeIsAbsent() {
            List<SkuCost> rows = List.of(
                cost(1L, SkuCostType.GOODS, 10_000, "2026-09-01T00:00:00Z"),
                cost(2L, SkuCostType.GOODS, 12_000, NOW.plusSeconds(60).toString()),
                cost(3L, SkuCostType.PACKAGING, 500, NOW.plusSeconds(60).toString())
            );

            assertThat(SkuCostTimeline.resolveAt(rows, NOW)).extracting(SkuCost::id).containsExactly(1L);
        }

        @Test
        @DisplayName("행이 없으면 빈 목록이다")
        void empty() {
            assertThat(SkuCostTimeline.resolveAt(List.of(), NOW)).isEmpty();
        }

        @Test
        @DisplayName("0원 행도 적용 원가다 — 미입력과 다르다")
        void zeroIsExplicit() {
            List<SkuCost> rows = List.of(cost(1L, SkuCostType.PACKAGING, 0, "2026-09-01T00:00:00Z"));

            assertThat(SkuCostTimeline.resolveAt(rows, NOW)).singleElement().extracting(cost -> cost.amount().amount()).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("validateAppend — 새 행 판정")
    class ValidateAppend {

        @Test
        @DisplayName("첫 행은 미래 한도만 본다 — 과거 시작도 받는다")
        void firstRowAcceptsPast() {
            assertThat(SkuCostTimeline.validateAppend(null, cost(1L, SkuCostType.GOODS, 10_000, "2020-01-01T00:00:00Z"), NOW)).isEmpty();
        }

        @Test
        @DisplayName("지금 + 5분까지는 받고, 그보다 뒤면 IN_FUTURE 다")
        void futureTolerance() {
            Instant limit = NOW.plus(SkuCostTimeline.FUTURE_TOLERANCE);

            assertThat(SkuCostTimeline.validateAppend(null, cost(1L, SkuCostType.GOODS, 1, limit.toString()), NOW)).isEmpty();
            assertThat(SkuCostTimeline.validateAppend(null, cost(1L, SkuCostType.GOODS, 1, limit.plusNanos(1_000).toString()), NOW))
                .contains(SkuCostAppendViolation.IN_FUTURE);
        }

        @Test
        @DisplayName("최신 행보다 뒤면 지금보다 과거(소급)여도 받는다")
        void backdatedAfterLatestIsAccepted() {
            SkuCost latest = cost(1L, SkuCostType.GOODS, 10_000, "2026-09-01T00:00:00Z");

            assertThat(SkuCostTimeline.validateAppend(latest, cost(2L, SkuCostType.GOODS, 12_000, "2026-09-15T00:00:00Z"), NOW)).isEmpty();
        }

        @Test
        @DisplayName("최신 행과 시작이 같거나 앞이면 NOT_AFTER_LATEST 다")
        void notAfterLatestIsRejected() {
            SkuCost latest = cost(1L, SkuCostType.GOODS, 10_000, "2026-10-01T00:00:00Z");

            assertThat(SkuCostTimeline.validateAppend(latest, cost(2L, SkuCostType.GOODS, 12_000, "2026-10-01T00:00:00Z"), NOW))
                .contains(SkuCostAppendViolation.NOT_AFTER_LATEST);
            assertThat(SkuCostTimeline.validateAppend(latest, cost(2L, SkuCostType.GOODS, 12_000, "2026-09-30T23:59:59.999999Z"), NOW))
                .contains(SkuCostAppendViolation.NOT_AFTER_LATEST);
        }

        @Test
        @DisplayName("기존 행과 통화가 다르면 CURRENCY_MISMATCH 다 — 시작 시각이 맞아도 거절한다")
        void currencyIsFixedByFirstRow() {
            SkuCost latest = cost(1L, SkuCostType.GOODS, 10_000, "2026-09-01T00:00:00Z");
            SkuCost usd = new SkuCost(2L, 100L, SkuCostType.GOODS, Money.of(900, CurrencyCode.USD), Instant.parse("2026-10-01T00:00:00Z"), "환율", 7L, null);

            assertThat(SkuCostTimeline.validateAppend(latest, usd, NOW)).contains(SkuCostAppendViolation.CURRENCY_MISMATCH);
        }

        @Test
        @DisplayName("위반이 여럿이면 미래 한도 → 통화 → 단조 순으로 하나만 낸다")
        void violationOrder() {
            SkuCost latest = cost(1L, SkuCostType.GOODS, 10_000, NOW.plus(Duration.ofDays(1)).toString());
            SkuCost futureUsd = new SkuCost(2L, 100L, SkuCostType.GOODS, Money.of(900, CurrencyCode.USD), NOW.plus(Duration.ofHours(1)), "환율", 7L, null);
            SkuCost pastUsd = new SkuCost(3L, 100L, SkuCostType.GOODS, Money.of(900, CurrencyCode.USD), NOW, "환율", 7L, null);

            assertThat(SkuCostTimeline.validateAppend(latest, futureUsd, NOW)).contains(SkuCostAppendViolation.IN_FUTURE);
            assertThat(SkuCostTimeline.validateAppend(latest, pastUsd, NOW)).contains(SkuCostAppendViolation.CURRENCY_MISMATCH);
        }
    }

    private static SkuCost cost(long id, SkuCostType type, long amount, String effectiveFrom) {
        return new SkuCost(id, 100L, type, Money.of(amount, CurrencyCode.KRW), Instant.parse(effectiveFrom), "단가 변경", 7L, null);
    }
}
