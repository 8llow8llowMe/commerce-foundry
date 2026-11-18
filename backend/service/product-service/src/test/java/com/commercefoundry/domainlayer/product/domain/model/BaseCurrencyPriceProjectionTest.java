package com.commercefoundry.domainlayer.product.domain.model;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BaseCurrencyPriceProjectionTest {
    @ParameterizedTest
    @EnumSource(CurrencyCode.class)
    void actualRowsWinAndAllNonPriceFieldsOrderAndInputsArePreserved(CurrencyCode base) {
        var legacy = product(base);
        var first = sku(11, 3, base, SaleStatus.STOP_SELLING);
        var second = sku(10, 9, base, SaleStatus.ON_SALE);
        var input = new ArrayList<>(List.of(first, second));
        var products = new ArrayList<>(List.of(new ProductCurrencyPrice(101, 1, Money.zero(base), Money.of(20, base))));
        var rows = new ArrayList<>(List.of(price(201, 10, 0, base), price(202, 11, 7, base)));
        var projection = BaseCurrencyPriceProjection.project(legacy, input, products, rows);
        assertThat(projection.product()).usingRecursiveComparison().ignoringFields("salePrice", "listPrice").isEqualTo(legacy);
        assertThat(projection.product().salePrice()).isEqualTo(Money.zero(base));
        assertThat(projection.product().listPrice()).isEqualTo(Money.of(20, base));
        assertThat(projection.skus()).extracting(Sku::id).containsExactly(11L, 10L);
        assertThat(projection.skus().get(0)).usingRecursiveComparison().ignoringFields("additionalPrice").isEqualTo(first);
        assertThat(projection.skus().get(1)).usingRecursiveComparison().ignoringFields("additionalPrice").isEqualTo(second);
        assertThat(projection.skus()).extracting(sku -> sku.additionalPrice().amount()).containsExactly(7L, 0L);
        assertThat(input).containsExactly(first, second);
        assertThat(legacy.salePrice()).isEqualTo(Money.of(100, base));
        assertThat(rows).containsExactly(price(201, 10, 0, base), price(202, 11, 7, base));
        input.clear();
        products.clear();
        rows.clear();
        assertThat(projection.skus()).hasSize(2);
        assertThat(projection.priceBook().productPrices()).hasSize(1);
        assertThat(projection.priceBook().skuPrices()).hasSize(2);
        assertThatThrownBy(() -> projection.skus().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(projection.toString()).isEqualTo("BaseCurrencyPriceProjection[redacted]");
        assertThatThrownBy(() -> LegacyPriceDrift.requireMatch(legacy, List.of(first, second), projection))
            .isExactlyInstanceOf(IllegalStateException.class).hasMessage("legacy 가격과 기준통화 가격행이 일치하지 않습니다").hasNoCause();
    }

    @Test
    void nullListStoppedOnlyAndAdditionalCurrencyPartialAndZeroSupportRemainValid() {
        var legacy = product(USD);
        var skus = List.of(sku(10, 0, USD, SaleStatus.STOP_SELLING), sku(11, 8, USD, SaleStatus.STOP_SELLING));
        var products = List.of(productPrice(101, 100, USD), productPrice(102, 0, KRW));
        var baseRows = List.of(price(201, 10, 3, USD), price(202, 11, 4, USD));
        var noExtraSupport = BaseCurrencyPriceProjection.project(legacy, skus, products, baseRows);
        assertThat(noExtraSupport.product().listPrice()).isNull();
        assertThat(noExtraSupport.priceBook().skuSalePrice(10, KRW)).isEmpty();
        var partial = BaseCurrencyPriceProjection.project(legacy, skus, products,
            List.of(baseRows.get(0), baseRows.get(1), price(203, 10, 0, KRW)));
        assertThat(partial.priceBook().skuSalePrice(10, KRW)).contains(Money.zero(KRW));
        assertThat(partial.priceBook().skuSalePrice(11, KRW)).isEmpty();
        assertThat(partial.priceBook().productPrices()).containsExactlyElementsOf(products);
    }

    @Test
    void missingDuplicateForeignDormantZeroAndOverflowInputsFailWithFixedErrors() {
        var legacy = product(KRW);
        var skus = List.of(sku(10, 0, KRW, SaleStatus.ON_SALE));
        var product = productPrice(101, 100, KRW);
        var row = price(201, 10, 0, KRW);
        for (var products : List.of(List.<ProductCurrencyPrice>of(), List.of(product, product),
            List.of(product, productPrice(102, 200, KRW)),
            List.of(new ProductCurrencyPrice(101, 2, Money.zero(KRW), null)))) {
            invalid(() -> BaseCurrencyPriceProjection.project(legacy, skus, products, List.of(row)));
        }
        for (var rows : List.of(List.<SkuCurrencyPrice>of(), List.of(row, row),
            List.of(row, price(202, 10, 0, KRW)), List.of(price(201, 99, 0, KRW)),
            List.of(row, price(202, 10, 0, USD)), List.of(price(201, 10, 1, KRW)))) {
            invalid(() -> BaseCurrencyPriceProjection.project(legacy, skus, List.of(product), rows));
        }
        invalid(() -> BaseCurrencyPriceProjection.project(legacy, skus,
            List.of(product, productPrice(102, Long.MAX_VALUE, USD)), List.of(row, price(202, 10, 1, USD))));
        invalid(() -> BaseCurrencyPriceProjection.project(legacy, skus,
            List.of(product, productPrice(102, 10, USD)), List.of(row, price(202, 10, 1, USD))));
        invalid(() -> BaseCurrencyPriceProjection.project(legacy, List.of(skus.get(0), skus.get(0)), List.of(product), List.of(row)));
        invalid(() -> BaseCurrencyPriceProjection.project(legacy, List.of(sku(10, 0, USD, SaleStatus.ON_SALE)), List.of(product), List.of(row)));
        invalid(() -> BaseCurrencyPriceProjection.project(legacy,
            List.of(new Sku(10, 2, SkuOptionKey.of(List.of()), Money.zero(KRW), SaleStatus.ON_SALE, null, null)), List.of(product), List.of(row)));
        var stopped = List.of(sku(10, 0, KRW, SaleStatus.STOP_SELLING));
        invalid(() -> BaseCurrencyPriceProjection.project(legacy, stopped,
            List.of(productPrice(101, Long.MAX_VALUE, KRW)), List.of(price(201, 10, 1, KRW))));
    }

    @Test
    void driftIsIndependentOfProjectionAndComparesListSkuAndIdentity() {
        var legacy = product(KRW).withPricing(Money.of(100, KRW), null);
        var skus = List.of(sku(10, 0, KRW, SaleStatus.ON_SALE), sku(11, 5, KRW, SaleStatus.STOP_SELLING));
        var projection = BaseCurrencyPriceProjection.project(legacy, skus, List.of(productPrice(101, 100, KRW)),
            List.of(price(201, 10, 0, KRW), price(202, 11, 5, KRW)));
        LegacyPriceDrift.requireMatch(legacy, List.of(skus.get(1), skus.get(0)), projection);
        for (var changed : List.of(legacy.withPricing(Money.of(99, KRW), null),
            legacy.withPricing(Money.of(100, KRW), Money.of(200, KRW)), product(USD))) {
            drift(() -> LegacyPriceDrift.requireMatch(changed, skus, projection));
        }
        drift(() -> LegacyPriceDrift.requireMatch(legacy, List.of(skus.get(0), sku(11, 6, KRW, SaleStatus.STOP_SELLING)), projection));
        drift(() -> LegacyPriceDrift.requireMatch(legacy, List.of(skus.get(0)), projection));
        drift(() -> LegacyPriceDrift.requireMatch(legacy, List.of(skus.get(0), sku(12, 5, KRW, SaleStatus.STOP_SELLING)), projection));
        assertThat(projection.product()).isEqualTo(legacy);
        assertThat(projection.skus()).containsExactlyElementsOf(skus);
    }

    private static Product product(CurrencyCode base) {
        return new Product(1, 9, "상품", "요약", "<p>상세</p>", SaleStatus.STOP_SELLING, false, Money.of(100, base), Money.of(200, base),
            7, true, "안내", 3, true, LocalDate.of(2030, 1, 2), 17L, Instant.parse("2030-01-01T00:00:00Z"), 41L,
            "product", "summary", "<p>detail</p>");
    }

    private static Sku sku(long id, long amount, CurrencyCode base, SaleStatus status) {
        return new Sku(id, 1, SkuOptionKey.of(List.of(id)), Money.of(amount, base), status, 123, new HsCode("123456"));
    }

    private static ProductCurrencyPrice productPrice(long id, long amount, CurrencyCode base) {
        return new ProductCurrencyPrice(id, 1, Money.of(amount, base), null);
    }

    private static SkuCurrencyPrice price(long id, long skuId, long amount, CurrencyCode base) {
        return new SkuCurrencyPrice(id, skuId, Money.of(amount, base));
    }

    private static void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("기준통화 가격 투영 입력이 올바르지 않습니다").hasNoCause();
    }

    private static void drift(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("legacy 가격과 기준통화 가격행이 일치하지 않습니다").hasNoCause();
    }
}
