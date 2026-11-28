package com.commercefoundry.domainlayer.product.application.service.processor;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.product.application.port.out.PriceBookRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceSnapshot;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceWritePlan.BaseRowMissingException;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceWritePlan.MissingBase;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuOptionKey;
import com.commercefoundry.global.properties.PriceSourceProperties;
import com.commercefoundry.global.properties.PriceSourceProperties.Mode;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * #20 B3: writer 진입의 빠진 기준통화 행 처리는 가격 정본 모드를 따른다(backfill 은 prepareBackfill 로 REPAIR 고정).
 *
 * <p>PRICE_BOOK 은 B7 까지 PriceSourceModeGuard 가 기동을 거절하므로 Spring 컨텍스트 대신 조율기를 모드별로 직접 만든다.
 * REJECT 예외(IllegalStateException)의 TX 전체 롤백은 같은 예외 계열인 drift 경로로 LegacyPriceWritersIntegrationTest 가 확인한다.
 */
class LegacyPriceWriteProcessorModeTest {
    private static final long PRODUCT = 1, SKU = 10;

    private final PriceBookRepositoryPort prices = mock(PriceBookRepositoryPort.class);
    private final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);

    @Test
    void legacyRepairsAndPriceBookRejectsMissingBaseRows() {
        assertThat(LegacyPriceWriteProcessor.missingBaseOf(Mode.LEGACY)).isEqualTo(MissingBase.REPAIR);
        assertThat(LegacyPriceWriteProcessor.missingBaseOf(Mode.PRICE_BOOK)).isEqualTo(MissingBase.REJECT);
    }

    @Test
    void priceBookWriterRejectsExistingProductWithoutBaseRowsAndAllocatesNoIds() {
        noPriceRows();
        var before = snapshot(1000);

        assertThatThrownBy(() -> processor(Mode.PRICE_BOOK).prepare(before, snapshot(1500)))
            .isExactlyInstanceOf(BaseRowMissingException.class).isInstanceOf(IllegalStateException.class)
            .hasMessage("기준통화 가격행이 없습니다");
        verifyNoInteractions(ids);
    }

    @Test
    void legacyWriterRepairsSameMissingRows() {
        noPriceRows();
        allocateIds();

        var plan = processor(Mode.LEGACY).prepare(snapshot(1000), snapshot(1500));

        assertThat(plan.productInserts()).singleElement().satisfies(p -> assertThat(p.salePrice()).isEqualTo(Money.of(1500, KRW)));
        assertThat(plan.skuInserts()).singleElement().satisfies(p -> assertThat(p.skuId()).isEqualTo(SKU));
    }

    @Test
    void backfillRepairsEvenWhenModeIsPriceBook() {
        noPriceRows();
        allocateIds();
        var snapshot = snapshot(1000);

        var plan = processor(Mode.PRICE_BOOK).prepareBackfill(snapshot, snapshot);

        assertThat(plan.productInserts()).singleElement().satisfies(p -> assertThat(p.salePrice()).isEqualTo(Money.of(1000, KRW)));
        assertThat(plan.skuInserts()).hasSize(1);
        assertThat(plan.productUpdates()).isEmpty();
        assertThat(plan.skuUpdates()).isEmpty();
    }

    private LegacyPriceWriteProcessor processor(Mode mode) {
        return new LegacyPriceWriteProcessor(prices, ids, new PriceSourceProperties(mode));
    }

    private void noPriceRows() {
        when(prices.findProductPrices(any())).thenReturn(List.of());
        when(prices.findSkuPrices(any())).thenReturn(List.of());
    }

    private void allocateIds() {
        var next = new AtomicLong(1000);
        when(ids.generateId()).thenAnswer(call -> next.incrementAndGet());
    }

    private static LegacyPriceSnapshot snapshot(long sale) {
        var sku = new Sku(SKU, PRODUCT, SkuOptionKey.of(List.of(SKU)), Money.of(0, KRW), SaleStatus.ON_SALE, null, null);
        return new LegacyPriceSnapshot(PRODUCT, Money.of(sale, KRW), null, List.of(sku));
    }
}
