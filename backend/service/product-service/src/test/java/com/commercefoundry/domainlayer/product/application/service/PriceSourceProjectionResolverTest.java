package com.commercefoundry.domainlayer.product.application.service;

import static com.commercefoundry.domainlayer.product.application.exception.PriceSourceProjectionException.Reason.INVALID_PRICE_BOOK;
import static com.commercefoundry.domainlayer.product.application.exception.PriceSourceProjectionException.Reason.INVALID_SNAPSHOT;
import static com.commercefoundry.domainlayer.product.application.exception.PriceSourceProjectionException.Reason.PRICE_DRIFT;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.product.application.exception.PriceSourceProjectionException;
import com.commercefoundry.domainlayer.product.application.exception.PriceSourceProjectionException.Reason;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceSourceSnapshotQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductCurrencyPrice;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuCurrencyPrice;
import com.commercefoundry.domainlayer.product.domain.model.SkuOptionKey;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PriceSourceProjectionResolverTest {
    private final Product krw = product(1, KRW);
    private final Product usd = product(2, USD);
    private final Sku stopped = sku(11, 1, KRW, SaleStatus.STOP_SELLING);
    private final Sku second = sku(12, 1, KRW, SaleStatus.ON_SALE);
    private final Sku foreign = sku(21, 2, USD, SaleStatus.ON_SALE);
    private final ProductCurrencyPrice krwBase = productPrice(100, 1, KRW, 100);
    private final ProductCurrencyPrice usdBase = productPrice(200, 2, USD, 100);
    private final SkuCurrencyPrice stoppedBase = skuPrice(110, 11, KRW, 0);
    private final SkuCurrencyPrice secondBase = skuPrice(120, 12, KRW, 0);
    private final SkuCurrencyPrice foreignBase = skuPrice(210, 21, USD, 0);

    @Test
    void resolvesInterleavedRowsInRequestAndSkuOrderAndKeepsPartialAdditionalCurrency() {
        var extraProduct = productPrice(101, 1, USD, 300);
        var extraSku = skuPrice(121, 12, USD, 0);
        var input = snapshot(List.of(krw, usd), List.of(second, foreign, stopped),
            List.of(usdBase, extraProduct, krwBase), List.of(stoppedBase, extraSku, foreignBase, secondBase));
        var result = PriceSourceProjectionResolver.resolve(input);

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().product()).isEqualTo(usd);
        assertThat(result.getFirst().skus()).containsExactly(foreign);
        assertThat(result.getLast().product()).isEqualTo(krw);
        assertThat(result.getLast().skus()).containsExactly(second, stopped);
        assertThat(result.getLast().priceBook().skuSalePrice(12, USD)).contains(Money.of(300, USD));
        assertThat(result.getLast().priceBook().skuSalePrice(11, USD)).isEmpty();
        assertThat(input.skus()).containsExactly(second, foreign, stopped);
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyBatchIsEmptyAndASkuLessProductStillRequiresItsBasePrice() {
        assertThat(PriceSourceProjectionResolver.resolve(new PriceSourceSnapshotQueryResult(List.of(), List.of(), List.of(), List.of(), List.of()))).isEmpty();
        var input = new PriceSourceSnapshotQueryResult(List.of(1L), List.of(krw), List.of(), List.of(krwBase), List.of());
        assertThat(PriceSourceProjectionResolver.resolve(input)).singleElement().satisfies(result -> assertThat(result.skus()).isEmpty());
        assertFailure(new PriceSourceSnapshotQueryResult(List.of(1L), List.of(krw), List.of(), List.of(), List.of()), INVALID_PRICE_BOOK);
    }

    @Test
    void rejectsMissingProductOrStoppedSkuPriceForTheWholeBatch() {
        assertFailure(snapshot(List.of(krw, usd), List.of(stopped, foreign), List.of(usdBase),
            List.of(stoppedBase, foreignBase)), INVALID_PRICE_BOOK);
        assertFailure(snapshot(List.of(krw, usd), List.of(stopped, foreign), List.of(krwBase, usdBase),
            List.of(foreignBase)), INVALID_PRICE_BOOK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sale", "list", "sku"})
    void rejectsLegacyDriftForTheWholeBatch(String field) {
        var changed = switch (field) {
            case "sale" -> krw.withPricing(Money.of(99, KRW), null);
            case "list" -> krw.withPricing(krw.salePrice(), Money.of(200, KRW));
            default -> krw;
        };
        var skuPrice = field.equals("sku") ? skuPrice(110, 11, KRW, 1) : stoppedBase;
        assertFailure(snapshot(List.of(usd, changed), List.of(foreign, stopped), List.of(usdBase, krwBase),
            List.of(foreignBase, skuPrice)), PRICE_DRIFT);
    }

    @Test
    void preservesExistingWholePriceBookValidation() {
        assertFailure(snapshot(List.of(krw, usd), List.of(second, foreign),
            List.of(krwBase, usdBase, productPrice(101, 1, USD, 300)),
            List.of(secondBase, foreignBase, skuPrice(121, 12, USD, 1))), INVALID_PRICE_BOOK);
    }

    @Test
    void rejectsInconsistentSnapshotMembershipAndDuplicates() {
        assertFailure(null, INVALID_SNAPSHOT);
        assertFailure(snapshot(List.of(krw), List.of(), List.of(krwBase), List.of()), INVALID_SNAPSHOT);
        assertFailure(snapshot(List.of(krw, krw), List.of(), List.of(krwBase), List.of()), INVALID_SNAPSHOT);
        assertFailure(new PriceSourceSnapshotQueryResult(List.of(1L, 1L), List.of(krw), List.of(), List.of(), List.of()), INVALID_SNAPSHOT);
        assertFailure(snapshot(List.of(krw, usd), List.of(stopped, stopped), List.of(krwBase, usdBase), List.of()), INVALID_SNAPSHOT);
        assertFailure(snapshot(List.of(krw, usd), List.of(sku(31, 3, KRW, SaleStatus.ON_SALE)), List.of(), List.of()), INVALID_SNAPSHOT);
        assertFailure(snapshot(List.of(krw, usd), List.of(), List.of(productPrice(300, 3, KRW, 0)), List.of()), INVALID_SNAPSHOT);
        assertFailure(snapshot(List.of(krw, usd), List.of(), List.of(krwBase, usdBase), List.of(stoppedBase)), INVALID_SNAPSHOT);
    }

    private static void assertFailure(PriceSourceSnapshotQueryResult input, Reason reason) {
        assertThatThrownBy(() -> PriceSourceProjectionResolver.resolve(input))
            .isExactlyInstanceOf(PriceSourceProjectionException.class).hasMessage(reason.name()).hasNoCause()
            .satisfies(error -> {
                assertThat(((PriceSourceProjectionException) error).reason()).isEqualTo(reason);
                assertThat(error.getSuppressed()).isEmpty();
            });
    }

    private static PriceSourceSnapshotQueryResult snapshot(
        List<Product> products, List<Sku> skus, List<ProductCurrencyPrice> productPrices, List<SkuCurrencyPrice> skuPrices
    ) {
        return new PriceSourceSnapshotQueryResult(List.of(2L, 1L), products, skus, productPrices, skuPrices);
    }

    private static Product product(long id, CurrencyCode currency) {
        return new Product(id, 9, "내부 상품", "요약", null, SaleStatus.STOP_SELLING, false, Money.of(100, currency), null,
            null, false, null, null, false, null, 17L, null, null);
    }

    private static Sku sku(long id, long owner, CurrencyCode currency, SaleStatus state) {
        return new Sku(id, owner, SkuOptionKey.of(List.of(id)), Money.of(0, currency), state, 123, null);
    }

    private static ProductCurrencyPrice productPrice(long id, long owner, CurrencyCode currency, long amount) {
        return new ProductCurrencyPrice(id, owner, Money.of(amount, currency), null);
    }

    private static SkuCurrencyPrice skuPrice(long id, long owner, CurrencyCode currency, long amount) {
        return new SkuCurrencyPrice(id, owner, Money.of(amount, currency));
    }
}
