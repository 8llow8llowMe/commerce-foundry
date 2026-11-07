package com.commercefoundry.domainlayer.product.domain.model;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceWritePlan.RequestRuleViolationException;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LegacyPriceWritePlanTest {
    @ParameterizedTest
    @EnumSource(CurrencyCode.class)
    void absentRowsGetOneIdEachAndInsertOnlyFinalAmounts(CurrencyCode currency) {
        var before = snapshot(currency, 100, sku(10, 0, SaleStatus.ON_SALE, currency), sku(11, 5, SaleStatus.STOP_SELLING, currency));
        var after = snapshot(currency, 200, sku(10, 0, SaleStatus.ON_SALE, currency), sku(11, 8, SaleStatus.STOP_SELLING, currency));
        var ids = new AtomicLong(1000);
        var plan = LegacyPriceWritePlan.prepare(before, after, List.of(), List.of(), ids::incrementAndGet);
        assertThat(ids.get()).isEqualTo(1003);
        assertThat(plan.productInserts()).containsExactly(product(1001, 200, currency));
        assertThat(plan.skuInserts()).containsExactly(price(1002, 10, 0, currency), price(1003, 11, 8, currency));
        assertThat(plan.productUpdates()).isEmpty();
        assertThat(plan.skuUpdates()).isEmpty();
    }

    @Test
    void partialRowsUpdateOnlyChangedExistingBaseAndPreserveAdditionalCurrency() {
        var before = snapshot(KRW, 100, sku(10, 0, SaleStatus.ON_SALE, KRW), sku(11, 5, SaleStatus.STOP_SELLING, KRW));
        var after = snapshot(KRW, 200, sku(10, 0, SaleStatus.ON_SALE, KRW), sku(11, 8, SaleStatus.STOP_SELLING, KRW));
        var ids = new AtomicLong(1000);
        var plan = LegacyPriceWritePlan.prepare(before, after, List.of(product(1, 100, KRW), product(2, 300, USD)),
            List.of(price(3, 10, 0, KRW), price(4, 10, 0, USD)), ids::incrementAndGet);
        assertThat(ids.get()).isEqualTo(1001);
        assertThat(plan.productInserts()).isEmpty();
        assertThat(plan.skuInserts()).containsExactly(price(1001, 11, 8, KRW));
        assertThat(plan.productUpdates()).containsExactly(product(1, 200, KRW));
        assertThat(plan.skuUpdates()).isEmpty();
        assertThatThrownBy(() -> plan.productUpdates().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void completeRowsProduceNoWritesAndChangedSkuKeepsItsId() {
        var before = snapshot(KRW, 100, sku(10, 0, SaleStatus.ON_SALE, KRW), sku(11, 5, SaleStatus.STOP_SELLING, KRW));
        var products = List.of(product(1, 100, KRW));
        var skus = List.of(price(3, 10, 0, KRW), price(4, 11, 5, KRW));
        var noop = LegacyPriceWritePlan.prepare(before, before, products, skus, noIds());
        assertThat(noop.productInserts()).isEmpty();
        assertThat(noop.skuInserts()).isEmpty();
        assertThat(noop.productUpdates()).isEmpty();
        assertThat(noop.skuUpdates()).isEmpty();
        var after = snapshot(KRW, 100, sku(10, 0, SaleStatus.ON_SALE, KRW), sku(11, 7, SaleStatus.STOP_SELLING, KRW));
        assertThat(LegacyPriceWritePlan.prepare(before, after, products, skus, noIds()).skuUpdates())
            .containsExactly(price(4, 11, 7, KRW));
    }

    @Test
    void newSkuGetsBaseRowOnlyAndUnexpectedRowsAreRejectedBeforeIdAllocation() {
        var before = snapshot(KRW, 100, sku(10, 0, SaleStatus.ON_SALE, KRW));
        var after = snapshot(KRW, 100, sku(10, 0, SaleStatus.ON_SALE, KRW), sku(11, 7, SaleStatus.STOP_SELLING, KRW));
        var products = List.of(product(1, 100, KRW));
        var existing = price(3, 10, 0, KRW);
        var ids = new AtomicLong(1000);
        assertThat(LegacyPriceWritePlan.prepare(before, after, products, List.of(existing), ids::incrementAndGet).skuInserts())
            .containsExactly(price(1001, 11, 7, KRW));
        for (var currency : CurrencyCode.values()) {
            assertInternal(() -> LegacyPriceWritePlan.prepare(before, after, products,
                List.of(existing, price(4, 11, 0, currency)), noIds()));
        }
    }

    @Test
    void driftOwnershipDuplicatesAndOrphansAreInternalErrors() {
        var before = snapshot(KRW, 100, sku(10, 0, SaleStatus.ON_SALE, KRW));
        var after = snapshot(KRW, 200, sku(10, 1, SaleStatus.ON_SALE, KRW));
        for (var products : List.of(List.of(product(1, 99, KRW)), List.of(product(1, 100, KRW), product(2, 100, KRW)),
            List.of(product(1, 100, KRW), product(1, 100, USD)), List.of(new ProductCurrencyPrice(1, 2, Money.zero(KRW), null)),
            List.of(new ProductCurrencyPrice(1, 1, Money.of(100, KRW), Money.of(150, KRW))))) {
            assertInternal(() -> LegacyPriceWritePlan.prepare(before, after, products, List.of(price(3, 10, 0, KRW)), noIds()));
        }
        for (var skus : List.of(List.of(price(3, 10, 1, KRW)), List.of(price(3, 10, 0, KRW), price(4, 10, 0, KRW)),
            List.of(price(3, 10, 0, KRW), price(3, 10, 0, USD)), List.of(price(3, 99, 0, KRW)), List.of(price(3, 10, 0, USD)))) {
            assertInternal(() -> LegacyPriceWritePlan.prepare(before, after, List.of(product(1, 100, KRW)), skus, noIds()));
        }
    }

    @Test
    void invalidBeforeZeroListAndOverflowRemainInternal() {
        var ids = new AtomicLong(1000);
        var badZero = snapshot(KRW, 100, sku(10, 1, SaleStatus.ON_SALE, KRW));
        assertInternal(() -> LegacyPriceWritePlan.prepare(badZero, badZero, List.of(), List.of(), ids::incrementAndGet));
        var badList = new LegacyPriceSnapshot(1, Money.of(100, KRW), Money.of(100, KRW), List.of());
        assertInternal(() -> LegacyPriceWritePlan.prepare(badList, badList, List.of(), List.of(), ids::incrementAndGet));
        var overflow = snapshot(KRW, Long.MAX_VALUE, sku(10, 1, SaleStatus.STOP_SELLING, KRW));
        assertInternal(() -> LegacyPriceWritePlan.prepare(overflow, overflow, List.of(), List.of(), ids::incrementAndGet));
    }

    @Test
    void validBeforeSeparatesAfterZeroListCurrencyAndOverflowRuleViolations() {
        var before = snapshot(KRW, 100, sku(10, 0, SaleStatus.ON_SALE, KRW));
        for (var after : List.of(snapshot(KRW, 100, sku(10, 1, SaleStatus.ON_SALE, KRW)),
            new LegacyPriceSnapshot(1, Money.of(100, KRW), Money.of(100, KRW), before.skus()),
            new LegacyPriceSnapshot(1, Money.of(100, KRW), Money.of(200, USD), before.skus()),
            snapshot(KRW, Long.MAX_VALUE, sku(10, 1, SaleStatus.STOP_SELLING, KRW)))) {
            assertThatThrownBy(() -> LegacyPriceWritePlan.prepare(before, after, List.of(product(1, 100, KRW)),
                List.of(price(3, 10, 0, KRW)), noIds())).isExactlyInstanceOf(RequestRuleViolationException.class).hasNoCause();
        }
    }

    @Test
    void additionalCurrencyZeroRuleUsesAfterStatusesAndZeroSupportedSkusStayAllowed() {
        var before = snapshot(KRW, 100, sku(10, 0, SaleStatus.ON_SALE, KRW), sku(11, 0, SaleStatus.ON_SALE, KRW));
        var after = snapshot(KRW, 100, sku(10, 0, SaleStatus.STOP_SELLING, KRW), sku(11, 0, SaleStatus.ON_SALE, KRW));
        var products = List.of(product(1, 100, KRW), product(2, 200, USD));
        var base = List.of(price(3, 10, 0, KRW), price(4, 11, 0, KRW));
        assertThatThrownBy(() -> LegacyPriceWritePlan.prepare(before, after, products,
            List.of(base.get(0), base.get(1), price(5, 10, 0, USD), price(6, 11, 1, USD)), noIds()))
            .isExactlyInstanceOf(RequestRuleViolationException.class);
        var partial = LegacyPriceWritePlan.prepare(before, after, products,
            List.of(base.get(0), base.get(1), price(5, 10, 0, USD)), noIds());
        assertThat(partial.skuInserts()).isEmpty();
        assertThat(partial.skuUpdates()).isEmpty();
        assertInternal(() -> LegacyPriceWritePlan.prepare(before, after, List.of(product(1, 100, KRW), product(2, Long.MAX_VALUE, USD)),
            List.of(base.get(0), base.get(1), price(5, 10, 0, USD), price(6, 11, 1, USD)), noIds()));
    }

    @ParameterizedTest
    @EnumSource(CurrencyCode.class)
    void newProductSupportsZeroNullableListAndStoppedSku(CurrencyCode currency) {
        var after = snapshot(currency, 0, sku(10, 7, SaleStatus.STOP_SELLING, currency));
        var ids = new AtomicLong(1000);
        var plan = LegacyPriceWritePlan.prepareNew(after, List.of(), List.of(), ids::incrementAndGet);
        assertThat(plan.productInserts()).containsExactly(product(1001, 0, currency));
        assertThat(plan.skuInserts()).containsExactly(price(1002, 10, 7, currency));
        assertThat(plan.productUpdates()).isEmpty();
        assertThat(plan.skuUpdates()).isEmpty();
        assertInternal(() -> LegacyPriceWritePlan.prepareNew(after, List.of(product(1, 0, currency)), List.of(), noIds()));
        assertInternal(() -> LegacyPriceWritePlan.prepareNew(after, List.of(), List.of(price(2, 10, 0, currency)), noIds()));
    }

    @Test
    void invalidNewProductIsRequestViolationAndIdFailureIsInternal() {
        var after = snapshot(KRW, 100, sku(10, 1, SaleStatus.ON_SALE, KRW));
        var ids = new AtomicLong(1000);
        assertThatThrownBy(() -> LegacyPriceWritePlan.prepareNew(after, List.of(), List.of(), ids::incrementAndGet))
            .isExactlyInstanceOf(RequestRuleViolationException.class);
        assertInternal(() -> LegacyPriceWritePlan.prepareNew(after, List.of(), List.of(), () -> 0));
        assertInternal(() -> LegacyPriceWritePlan.prepareNew(after, List.of(), List.of(), () -> { throw new IllegalArgumentException("private"); }));
        var two = snapshot(KRW, 100, sku(10, 0, SaleStatus.ON_SALE, KRW), sku(11, 0, SaleStatus.STOP_SELLING, KRW));
        assertInternal(() -> LegacyPriceWritePlan.prepareNew(two, List.of(), List.of(), () -> 2));
        assertInternal(() -> LegacyPriceWritePlan.prepare(two, two, List.of(product(1, 100, KRW)),
            List.of(price(3, 10, 0, KRW)), () -> 3));
    }

    @Test
    void snapshotCopiesAllSkusAndRejectsForeignDuplicateOrChangedIdentity() {
        var input = new ArrayList<>(List.of(sku(10, 0, SaleStatus.STOP_SELLING, KRW)));
        var before = new LegacyPriceSnapshot(1, Money.zero(KRW), null, input);
        input.clear();
        assertThat(before.skus()).hasSize(1);
        assertThatThrownBy(() -> before.skus().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertInternal(() -> new LegacyPriceSnapshot(1, Money.zero(KRW), null, List.of(before.skus().get(0), before.skus().get(0))));
        assertInternal(() -> new LegacyPriceSnapshot(2, Money.zero(KRW), null, before.skus()));
        assertInternal(() -> new LegacyPriceSnapshot(1, Money.zero(USD), null, before.skus()));
        assertInternal(() -> before.requireSuccessor(snapshot(KRW, 0)));
        assertInternal(() -> before.requireSuccessor(snapshot(USD, 0, sku(10, 0, SaleStatus.STOP_SELLING, USD))));
        assertInternal(() -> before.requireSuccessor(new LegacyPriceSnapshot(2, Money.zero(KRW), null, List.of())));
    }

    private static LegacyPriceSnapshot snapshot(CurrencyCode currency, long sale, Sku... skus) {
        return new LegacyPriceSnapshot(1, Money.of(sale, currency), null, List.of(skus));
    }

    private static Sku sku(long id, long amount, SaleStatus status, CurrencyCode currency) {
        return new Sku(id, 1, SkuOptionKey.of(List.of(id)), Money.of(amount, currency), status, null, null);
    }

    private static ProductCurrencyPrice product(long id, long sale, CurrencyCode currency) {
        return new ProductCurrencyPrice(id, 1, Money.of(sale, currency), null);
    }

    private static SkuCurrencyPrice price(long id, long skuId, long amount, CurrencyCode currency) {
        return new SkuCurrencyPrice(id, skuId, Money.of(amount, currency));
    }

    private static LongSupplier noIds() {
        return () -> { throw new AssertionError("ID must not be allocated"); };
    }

    private static void assertInternal(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isExactlyInstanceOf(IllegalStateException.class).hasNoCause();
    }
}
