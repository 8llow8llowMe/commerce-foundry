package com.commercefoundry.domainlayer.product.domain.model;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PriceBookTest {
    private static final Map<Long, SaleStatus> SKUS = Map.of(10L, SaleStatus.ON_SALE, 11L, SaleStatus.STOP_SELLING);
    private static final ProductCurrencyPrice KRW_PRICE = product(1, 100, KRW);
    private static final ProductCurrencyPrice USD_PRICE = product(2, 0, USD);
    private static final SkuCurrencyPrice FIRST = sku(3, 10, 0, KRW);
    private static final SkuCurrencyPrice STOPPED = sku(4, 11, 50, KRW);

    @Test
    void additionalCurrencyIsPartialAndMissingDiffersFromRealZero() {
        var book = book(List.of(KRW_PRICE, USD_PRICE), List.of(FIRST, STOPPED, sku(5, 10, 0, USD)));
        assertThat(book.skuSalePrice(10, USD)).contains(Money.zero(USD));
        assertThat(book.skuSalePrice(11, USD)).isEmpty();
        assertThat(book.skuSalePrice(11, KRW)).contains(Money.of(150, KRW));
        assertThatThrownBy(() -> book.skuStatuses().put(12L, SaleStatus.ON_SALE)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void baseCurrencyRequiresEveryLegacySkuEvenStoppedAndSupportsExistingUsdBase() {
        assertThatThrownBy(() -> book(List.of(KRW_PRICE), List.of(FIRST))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> book(List.of(USD_PRICE), List.of(FIRST, STOPPED))).isInstanceOf(IllegalArgumentException.class);
        var usd = new PriceBook(1, USD, SKUS, List.of(USD_PRICE), List.of(sku(1, 10, 0, USD), sku(2, 11, 2, USD)));
        assertThat(usd.skuSalePrice(11, USD)).contains(Money.of(2, USD));
    }

    @Test
    void zeroRuleUsesSupportedOnSaleSkusRegardlessOfInventory() {
        assertThatThrownBy(() -> book(List.of(KRW_PRICE), List.of(sku(3, 10, 1, KRW), STOPPED)))
            .isInstanceOf(IllegalArgumentException.class);
        var stopped = new PriceBook(1, KRW, Map.of(10L, SaleStatus.STOP_SELLING), List.of(KRW_PRICE), List.of(sku(3, 10, 1, KRW)));
        assertThat(stopped.skuSalePrice(10, KRW)).contains(Money.of(101, KRW));
    }

    @Test
    void rejectsWrongMembershipOrphansAndDuplicateLogicalOrPhysicalIds() {
        for (var rows : List.of(List.of(FIRST, STOPPED, sku(5, 99, 0, KRW)), List.of(FIRST, STOPPED, sku(5, 10, 0, USD)),
            List.of(FIRST, STOPPED, sku(5, 10, 0, KRW)), List.of(FIRST, sku(3, 11, 0, KRW)))) {
            assertThatThrownBy(() -> book(List.of(KRW_PRICE), rows)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> book(List.of(KRW_PRICE, product(2, 200, KRW)), List.of(FIRST, STOPPED)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> book(List.of(new ProductCurrencyPrice(2, 99, Money.zero(USD), null), KRW_PRICE), List.of(FIRST, STOPPED)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidAmountsCurrencyPairsIdsAndOverflowWithoutNewUpperLimit() {
        assertThatThrownBy(() -> new ProductCurrencyPrice(0, 1, Money.zero(KRW), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkuCurrencyPrice(1, 0, Money.zero(KRW))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkuCurrencyPrice(1, 10, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProductCurrencyPrice(1, 1, Money.of(1, KRW), Money.of(2, USD))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductCurrencyPrice(1, 1, Money.of(1, KRW), Money.of(1, KRW))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(-1, USD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> book(List.of(product(1, Long.MAX_VALUE, KRW)), List.of(FIRST, STOPPED)))
            .isInstanceOf(ArithmeticException.class);
        assertThat(new ProductCurrencyPrice(1, 1, Money.of(Long.MAX_VALUE, KRW), null).salePrice().amount()).isEqualTo(Long.MAX_VALUE);
    }

    private static PriceBook book(List<ProductCurrencyPrice> products, List<SkuCurrencyPrice> skus) {
        return new PriceBook(1, KRW, SKUS, products, skus);
    }

    private static ProductCurrencyPrice product(long id, long amount, com.commercefoundry.shared.commerce.CurrencyCode currency) {
        return new ProductCurrencyPrice(id, 1, Money.of(amount, currency), null);
    }

    private static SkuCurrencyPrice sku(long id, long skuId, long amount, com.commercefoundry.shared.commerce.CurrencyCode currency) {
        return new SkuCurrencyPrice(id, skuId, Money.of(amount, currency));
    }
}
