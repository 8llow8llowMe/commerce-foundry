package com.commercefoundry.domainlayer.skucost.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.skucost.application.command.SkuCostAtQueryCommand.Point;
import com.commercefoundry.domainlayer.skucost.application.info.SkuCostsAtInfo;
import com.commercefoundry.domainlayer.skucost.application.port.out.SkuCostRepositoryPort;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCost;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 서비스 간 시점 원가 — 원가 행을 한 번 읽어 (SKU, 시각) 쌍마다 유형별 적용 행을 고른다. 시작 시각은 포함이고, 원가가 없는 유형 · SKU 는 0 으로 채우지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class SkuCostQueryProcessorTest {

    private static final long SKU_A = 100L;
    private static final long SKU_B = 200L;
    private static final long SKU_EMPTY = 300L;

    // SKU_A: GOODS 9/1 10,000 → 10/1 12,000 · PACKAGING 10/15 500
    // SKU_B: PACKAGING 9/1 300 만 (GOODS 미입력)
    private static final SkuCost A_GOODS_SEP = cost(1L, SKU_A, SkuCostType.GOODS, 10_000, "2026-09-01T00:00:00Z");
    private static final SkuCost A_GOODS_OCT = cost(2L, SKU_A, SkuCostType.GOODS, 12_000, "2026-10-01T00:00:00Z");
    private static final SkuCost A_PACKAGING = cost(3L, SKU_A, SkuCostType.PACKAGING, 500, "2026-10-15T00:00:00Z");
    private static final SkuCost B_PACKAGING = cost(4L, SKU_B, SkuCostType.PACKAGING, 300, "2026-09-01T00:00:00Z");

    @Mock
    private SkuCostRepositoryPort skuCostRepositoryPort;

    private SkuCostQueryProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SkuCostQueryProcessor(skuCostRepositoryPort, Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("한 번의 조회(중복 없는 SKU · 가장 늦은 시각)로 읽고, 같은 SKU 라도 시각마다 다른 행을 고른다 — 요청 순서를 지킨다")
    void picksRowPerPointWithOneRead() {
        Instant latest = Instant.parse("2026-10-20T00:00:00Z");
        when(skuCostRepositoryPort.findAllUpTo(Set.of(SKU_A, SKU_B), latest)).thenReturn(List.of(A_PACKAGING, B_PACKAGING, A_GOODS_OCT, A_GOODS_SEP));

        List<SkuCostsAtInfo> result = processor.getCostsAt(List.of(
            point(SKU_A, "2026-10-20T00:00:00Z"),
            point(SKU_B, "2026-10-01T00:00:00Z"),
            point(SKU_A, "2026-09-15T00:00:00Z")
        ));

        assertThat(result).extracting(SkuCostsAtInfo::skuId).containsExactly(SKU_A, SKU_B, SKU_A);
        assertThat(result.get(0).costs()).as("GOODS → PACKAGING 순").containsExactly(A_GOODS_OCT, A_PACKAGING);
        assertThat(result.get(1).costs()).as("GOODS 미입력은 빠진다").containsExactly(B_PACKAGING);
        assertThat(result.get(2).costs()).as("9월 시점은 9월 행, 포장비는 아직 없다").containsExactly(A_GOODS_SEP);
        verify(skuCostRepositoryPort).findAllUpTo(Set.of(SKU_A, SKU_B), latest);
    }

    @Test
    @DisplayName("경계 — 시각이 적용 시작과 같으면 그 행이다(포함), 1마이크로초 앞이면 이전 행이다")
    void effectiveFromIsInclusive() {
        when(skuCostRepositoryPort.findAllUpTo(any(), any())).thenReturn(List.of(A_GOODS_SEP, A_GOODS_OCT));

        List<SkuCostsAtInfo> result = processor.getCostsAt(List.of(
            point(SKU_A, "2026-10-01T00:00:00Z"),
            point(SKU_A, "2026-09-30T23:59:59.999999Z"),
            point(SKU_A, "2026-08-31T23:59:59.999999Z")
        ));

        assertThat(result.get(0).costs()).containsExactly(A_GOODS_OCT);
        assertThat(result.get(1).costs()).containsExactly(A_GOODS_SEP);
        assertThat(result.get(2).costs()).as("첫 행 시작 전이면 빈 목록").isEmpty();
    }

    @Test
    @DisplayName("원가 행이 없는 SKU 는 빈 목록이다 — 0 으로 채우지 않는다")
    void skuWithoutCostsIsEmpty() {
        when(skuCostRepositoryPort.findAllUpTo(any(), any())).thenReturn(List.of());

        List<SkuCostsAtInfo> result = processor.getCostsAt(List.of(point(SKU_EMPTY, "2026-10-01T00:00:00Z")));

        assertThat(result).singleElement().satisfies(info -> {
            assertThat(info.skuId()).isEqualTo(SKU_EMPTY);
            assertThat(info.at()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
            assertThat(info.costs()).isEmpty();
        });
    }

    @Test
    @DisplayName("쌍이 없으면 조회하지 않고 빈 목록이다")
    void emptyPointsDoNotQuery() {
        assertThat(processor.getCostsAt(List.of())).isEmpty();
        verify(skuCostRepositoryPort, never()).findAllUpTo(anyCollection(), any());
    }

    private static Point point(long skuId, String at) {
        return new Point(skuId, Instant.parse(at));
    }

    private static SkuCost cost(long id, long skuId, SkuCostType type, long amount, String effectiveFrom) {
        return new SkuCost(id, skuId, type, Money.of(amount, CurrencyCode.KRW), Instant.parse(effectiveFrom), "단가", 7L, Instant.parse("2026-08-01T00:00:00Z"));
    }
}
