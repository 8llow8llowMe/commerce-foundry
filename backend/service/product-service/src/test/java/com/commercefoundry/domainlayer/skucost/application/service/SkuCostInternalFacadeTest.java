package com.commercefoundry.domainlayer.skucost.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.product.application.service.processor.SkuLookupProcessor;
import com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.item.SkuCostAtInternalItem;
import com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.response.SkuCostAtQueryInternalResponse;
import com.commercefoundry.domainlayer.skucost.adapter.in.internal.presenter.SkuCostInternalPresenter;
import com.commercefoundry.domainlayer.skucost.application.command.SkuCostAtQueryCommand;
import com.commercefoundry.domainlayer.skucost.application.command.SkuCostAtQueryCommand.Point;
import com.commercefoundry.domainlayer.skucost.application.port.out.SkuCostRepositoryPort;
import com.commercefoundry.domainlayer.skucost.application.service.processor.SkuCostQueryProcessor;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCost;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 서비스 간 시점 원가 조회의 조립 — 없는 SKU 는 응답에서 빠지고, 있는 SKU 는 원가가 없어도 빈 costs 로 남는다. 순서는 정규화한 요청 순서다.
 * Processor · Presenter 는 실제 객체이고 SKU 존재 확인과 원가 저장소만 흉내 낸다.
 */
@ExtendWith(MockitoExtension.class)
class SkuCostInternalFacadeTest {

    private static final long SKU_A = 100L;
    private static final long SKU_MISSING = 200L;
    private static final long SKU_NO_COST = 300L;
    private static final Instant AT = Instant.parse("2026-10-29T01:00:00Z");

    @Mock
    private SkuLookupProcessor skuLookupProcessor;

    @Mock
    private SkuCostRepositoryPort skuCostRepositoryPort;

    private SkuCostInternalFacade facade;

    @BeforeEach
    void setUp() {
        facade = new SkuCostInternalFacade(skuLookupProcessor, new SkuCostQueryProcessor(skuCostRepositoryPort, Clock.systemUTC()), new SkuCostInternalPresenter());
    }

    @Test
    @DisplayName("없는 SKU 는 빠지고, 원가가 없는 SKU 는 빈 costs 로 남는다 — 원가 조회에는 있는 SKU 만 넘긴다")
    void excludesMissingSkuAndKeepsEmptyCosts() {
        when(skuLookupProcessor.findExistingIds(Set.of(SKU_A, SKU_MISSING, SKU_NO_COST))).thenReturn(Set.of(SKU_A, SKU_NO_COST));
        when(skuCostRepositoryPort.findAllUpTo(Set.of(SKU_A, SKU_NO_COST), AT)).thenReturn(List.of(
            cost(1L, SKU_A, SkuCostType.PACKAGING, 500), cost(2L, SKU_A, SkuCostType.GOODS, 12_000)
        ));

        SkuCostAtQueryInternalResponse response = facade.getCostsAt(new SkuCostAtQueryCommand(List.of(
            new Point(SKU_MISSING, AT), new Point(SKU_NO_COST, AT), new Point(SKU_A, AT)
        )));

        assertThat(response.items()).extracting(SkuCostAtInternalItem::skuId).containsExactly("300", "100");
        assertThat(response.items().get(0).costs()).isEmpty();
        assertThat(response.items().get(1).costs()).extracting(cost -> cost.costType().code()).containsExactly("GOODS", "PACKAGING");
        assertThat(response.items().get(1).costs().get(0).amount().amount()).isEqualTo(12_000L);
        assertThat(response.items().get(1).costs().get(0).amount().currency()).isEqualTo("KRW");
    }

    @Test
    @DisplayName("SKU 가 하나도 없으면 원가를 조회하지 않고 빈 items 다")
    void allMissing() {
        when(skuLookupProcessor.findExistingIds(Set.of(SKU_MISSING))).thenReturn(Set.of());

        SkuCostAtQueryInternalResponse response = facade.getCostsAt(new SkuCostAtQueryCommand(List.of(new Point(SKU_MISSING, AT))));

        assertThat(response.items()).isEmpty();
        verify(skuCostRepositoryPort, never()).findAllUpTo(anyCollection(), any());
    }

    @Test
    @DisplayName("명령은 시각을 마이크로초로 잘라 같은 쌍을 합치고 처음 나온 순서를 지킨다 — SKU 아이디도 처음 나온 순서다")
    void commandNormalizes() {
        SkuCostAtQueryCommand command = new SkuCostAtQueryCommand(List.of(
            new Point(SKU_A, Instant.parse("2026-10-29T01:00:00.000000999Z")),
            new Point(SKU_NO_COST, AT),
            new Point(SKU_A, AT)
        ));

        assertThat(command.points()).containsExactly(new Point(SKU_A, AT), new Point(SKU_NO_COST, AT));
        assertThat(command.skuIds()).containsExactly(SKU_A, SKU_NO_COST);
    }

    private static SkuCost cost(long id, long skuId, SkuCostType type, long amount) {
        return new SkuCost(id, skuId, type, Money.of(amount, CurrencyCode.KRW), Instant.parse("2026-10-01T00:00:00Z"), "단가", 7L, Instant.parse("2026-09-30T09:00:00Z"));
    }
}
