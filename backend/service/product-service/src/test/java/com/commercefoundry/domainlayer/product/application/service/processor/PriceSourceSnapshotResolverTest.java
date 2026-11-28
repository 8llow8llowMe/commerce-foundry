package com.commercefoundry.domainlayer.product.application.service.processor;

import static com.commercefoundry.domainlayer.product.application.service.processor.PriceSourceSnapshotResolver.Reason.FOREIGN_ROW;
import static com.commercefoundry.domainlayer.product.application.service.processor.PriceSourceSnapshotResolver.Reason.INVALID_LEGACY;
import static com.commercefoundry.domainlayer.product.application.service.processor.PriceSourceSnapshotResolver.Reason.INVALID_PRICE_BOOK;
import static com.commercefoundry.domainlayer.product.application.service.processor.PriceSourceSnapshotResolver.Reason.INVALID_SNAPSHOT;
import static com.commercefoundry.domainlayer.product.application.service.processor.PriceSourceSnapshotResolver.Reason.LEGACY_DRIFT;
import static com.commercefoundry.domainlayer.product.application.service.processor.PriceSourceSnapshotResolver.Reason.PRODUCT_SET_MISMATCH;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class PriceSourceSnapshotResolverTest {
    @Test
    void groupsByOwnerAndPreservesRequestAndSkuOrder() {
        var input = new PriceSourceSnapshotQueryResult(List.of(2L, 1L), List.of(product(1, KRW), product(2, USD)),
            List.of(sku(20, 2, USD), sku(11, 1, KRW), sku(10, 1, KRW)),
            List.of(price(102, 2, USD), price(101, 1, KRW)),
            List.of(extra(220, 20, USD), extra(211, 11, KRW), extra(210, 10, KRW)));

        var resolved = PriceSourceSnapshotResolver.resolve(input);

        assertThat(resolved).extracting(row -> row.product().id()).containsExactly(2L, 1L);
        assertThat(resolved.get(1).skus()).extracting(Sku::id).containsExactly(11L, 10L);
        assertThat(resolved.get(0).product().salePrice()).isEqualTo(Money.of(100, USD));
        assertThatThrownBy(() -> resolved.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(input.skus()).extracting(Sku::id).containsExactly(20L, 11L, 10L);
    }

    @Test
    void rejectsMissingProductAndForeignRowsBeforeProjection() {
        var valid = valid();
        fails(new PriceSourceSnapshotQueryResult(List.of(1L, 2L), valid.products(), valid.skus(),
            valid.productPrices(), valid.skuPrices()), PRODUCT_SET_MISMATCH);
        fails(new PriceSourceSnapshotQueryResult(List.of(1L), valid.products(), List.of(sku(10, 2, KRW)),
            valid.productPrices(), valid.skuPrices()), FOREIGN_ROW);
        fails(new PriceSourceSnapshotQueryResult(List.of(1L), valid.products(), valid.skus(),
            List.of(price(101, 2, KRW)), valid.skuPrices()), FOREIGN_ROW);
        fails(new PriceSourceSnapshotQueryResult(List.of(1L), valid.products(), valid.skus(),
            valid.productPrices(), List.of(extra(201, 99, KRW))), FOREIGN_ROW);
        fails(new PriceSourceSnapshotQueryResult(List.of(1L), valid.products(), List.of(valid.skus().get(0), valid.skus().get(0)),
            valid.productPrices(), valid.skuPrices()), INVALID_SNAPSHOT);
    }

    @Test
    void rejectsDuplicateRowIdsAcrossProductsAndReturnsNoPartialBatch() {
        var parents = List.of(product(1, KRW), product(2, USD));
        var skus = List.of(sku(10, 1, KRW), sku(20, 2, USD));
        fails(new PriceSourceSnapshotQueryResult(List.of(1L, 2L), parents, skus,
            List.of(price(101, 1, KRW), price(101, 2, USD)),
            List.of(extra(201, 10, KRW), extra(202, 20, USD))), INVALID_SNAPSHOT);
        fails(new PriceSourceSnapshotQueryResult(List.of(1L, 2L), parents, skus,
            List.of(price(101, 1, KRW), price(102, 2, USD)),
            List.of(extra(201, 10, KRW), extra(201, 20, USD))), INVALID_SNAPSHOT);
        fails(new PriceSourceSnapshotQueryResult(List.of(1L, 2L), parents, skus,
            List.of(price(101, 1, KRW), price(102, 2, USD)),
            List.of(extra(201, 10, KRW))), INVALID_PRICE_BOOK);
    }

    @Test
    void distinguishesMissingOrInvalidPriceRowsFromLegacyDrift() {
        var valid = valid();
        fails(new PriceSourceSnapshotQueryResult(valid.productIds(), valid.products(), valid.skus(),
            List.of(), valid.skuPrices()), INVALID_PRICE_BOOK);
        fails(new PriceSourceSnapshotQueryResult(valid.productIds(), valid.products(), valid.skus(),
            valid.productPrices(), List.of()), INVALID_PRICE_BOOK);
        fails(new PriceSourceSnapshotQueryResult(valid.productIds(), valid.products(), valid.skus(),
            List.of(price(101, 1, USD)), valid.skuPrices()), INVALID_PRICE_BOOK);
        fails(new PriceSourceSnapshotQueryResult(valid.productIds(), valid.products(), valid.skus(),
            List.of(new ProductCurrencyPrice(101, 1, Money.of(101, KRW), null)), valid.skuPrices()), LEGACY_DRIFT);
        fails(new PriceSourceSnapshotQueryResult(valid.productIds(), valid.products(), valid.skus(),
            valid.productPrices(), List.of(new SkuCurrencyPrice(201, 10, Money.of(1, KRW)))), LEGACY_DRIFT);
        fails(new PriceSourceSnapshotQueryResult(valid.productIds(), valid.products(), List.of(sku(10, 1, USD)),
            valid.productPrices(), valid.skuPrices()), INVALID_LEGACY);
    }

    /** B4b — 상품별 결과. 실패한 상품은 사유만 갖고 나머지는 투영된다. resolve 는 같은 입력을 요청 순서상 처음 실패한 사유로 전체 거절한다. */
    @Test
    void resolveEachReportsPerProductOutcomesInRequestOrder() {
        var input = new PriceSourceSnapshotQueryResult(List.of(3L, 1L, 2L, 4L),
            List.of(product(1, KRW), product(2, KRW), product(3, KRW), product(4, KRW)),
            List.of(sku(10, 1, KRW), sku(20, 2, KRW), sku(30, 3, KRW), sku(40, 4, USD)),
            List.of(price(101, 1, KRW), new ProductCurrencyPrice(102, 2, Money.of(101, KRW), null), price(103, 3, KRW), price(104, 4, KRW)),
            List.of(extra(201, 10, KRW), extra(202, 20, KRW), extra(204, 40, KRW)));

        var outcomes = PriceSourceSnapshotResolver.resolveEach(input);

        assertThat(outcomes).extracting(PriceSourceSnapshotResolver.Outcome::productId).containsExactly(3L, 1L, 2L, 4L);
        assertThat(outcomes).extracting(PriceSourceSnapshotResolver.Outcome::failure)
            .containsExactly(INVALID_PRICE_BOOK, null, LEGACY_DRIFT, INVALID_LEGACY);
        assertThat(outcomes.get(1).isResolved()).isTrue();
        assertThat(outcomes.get(1).projection().product().salePrice()).isEqualTo(Money.of(100, KRW));
        assertThat(outcomes.get(1).projection().skus()).extracting(Sku::id).containsExactly(10L);
        assertThat(outcomes).filteredOn(outcome -> !outcome.isResolved()).allSatisfy(outcome -> assertThat(outcome.projection()).isNull());
        assertThat(outcomes.get(0).toString()).isEqualTo("Outcome[failure=INVALID_PRICE_BOOK]");
        assertThatThrownBy(() -> outcomes.clear()).isInstanceOf(UnsupportedOperationException.class);
        fails(input, INVALID_PRICE_BOOK);
    }

    @Test
    void resolveEachStillRejectsStructuralFailuresForTheWholeBatch() {
        var valid = valid();
        failsEach(new PriceSourceSnapshotQueryResult(List.of(1L, 2L), valid.products(), valid.skus(),
            valid.productPrices(), valid.skuPrices()), PRODUCT_SET_MISMATCH);
        failsEach(new PriceSourceSnapshotQueryResult(List.of(1L), valid.products(), valid.skus(),
            valid.productPrices(), List.of(extra(201, 99, KRW))), FOREIGN_ROW);
        failsEach(new PriceSourceSnapshotQueryResult(List.of(1L), valid.products(), valid.skus(),
            List.of(price(101, 1, KRW), price(101, 1, KRW)), valid.skuPrices()), INVALID_SNAPSHOT);
        failsEach(null, INVALID_SNAPSHOT);
    }

    @Test
    void emptyAndSkuFreeSnapshotsAreImmutable() {
        assertThat(PriceSourceSnapshotResolver.resolve(new PriceSourceSnapshotQueryResult(
            List.of(), List.of(), List.of(), List.of(), List.of()))).isEmpty();
        var result = PriceSourceSnapshotResolver.resolve(new PriceSourceSnapshotQueryResult(
            List.of(1L), List.of(product(1, KRW)), List.of(), List.of(price(101, 1, KRW)), List.of()));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).skus()).isEmpty();
        assertThatThrownBy(() -> result.get(0).skus().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static PriceSourceSnapshotQueryResult valid() {
        return new PriceSourceSnapshotQueryResult(List.of(1L), List.of(product(1, KRW)), List.of(sku(10, 1, KRW)),
            List.of(price(101, 1, KRW)), List.of(extra(201, 10, KRW)));
    }

    private static Product product(long id, CurrencyCode currency) {
        return new Product(id, 9, "상품", "요약", "상세", SaleStatus.STOP_SELLING, false,
            Money.of(100, currency), null, null, false, null, null, false, null, 1L, null, null);
    }

    private static Sku sku(long id, long productId, CurrencyCode currency) {
        return new Sku(id, productId, SkuOptionKey.of(List.of(id)), Money.zero(currency), SaleStatus.STOP_SELLING, null, null);
    }

    private static ProductCurrencyPrice price(long id, long productId, CurrencyCode currency) {
        return new ProductCurrencyPrice(id, productId, Money.of(100, currency), null);
    }

    private static SkuCurrencyPrice extra(long id, long skuId, CurrencyCode currency) {
        return new SkuCurrencyPrice(id, skuId, Money.zero(currency));
    }

    private static void fails(PriceSourceSnapshotQueryResult snapshot, PriceSourceSnapshotResolver.Reason reason) {
        assertThatThrownBy(() -> PriceSourceSnapshotResolver.resolve(snapshot))
            .isInstanceOf(PriceSourceSnapshotResolver.ResolutionException.class)
            .hasMessage(reason.name()).hasNoCause()
            .extracting("reason").isEqualTo(reason);
    }

    private static void failsEach(PriceSourceSnapshotQueryResult snapshot, PriceSourceSnapshotResolver.Reason reason) {
        assertThatThrownBy(() -> PriceSourceSnapshotResolver.resolveEach(snapshot))
            .isInstanceOf(PriceSourceSnapshotResolver.ResolutionException.class)
            .hasMessage(reason.name()).hasNoCause()
            .extracting("reason").isEqualTo(reason);
    }
}
