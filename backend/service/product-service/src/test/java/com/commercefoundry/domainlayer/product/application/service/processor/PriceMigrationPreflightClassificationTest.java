package com.commercefoundry.domainlayer.product.application.service.processor;

import static com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Finding.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Finding;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Grade;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Report;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Verdict;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Lane;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Processor 순회 + Classifier 분류. 작은 페이지로 돌려 페이지 경계의 중복 집계가 없음도 함께 본다. */
class PriceMigrationPreflightClassificationTest {
    static final String ON = "ON_SALE";
    static final String STOP = "STOP_SELLING";

    /** KRW 기준 P1(정가 포함)·USD 기준 P2, 각 추가 통화 가격까지 정상인 원천. 기준통화 가격행은 mirror()로 채운다. */
    static PriceMigrationReadStub normal() {
        return new PriceMigrationReadStub()
            .product(1, 10_000L, "KRW", 12_000L, "KRW").sku(11, 1L, 0L, "KRW", ON).sku(12, 1L, 500L, "KRW", ON)
            .product(2, 1_500L, "USD", null, null).sku(21, 2L, 0L, "USD", ON).sku(22, 2L, 300L, "USD", STOP)
            .productPrice(201, 1L, 800L, "USD", null, null).skuPrice(211, 11L, 0L, "USD").skuPrice(212, 12L, 50L, "USD")
            .productPrice(202, 2L, 2_000L, "KRW", 3_000L, "KRW").skuPrice(221, 21L, 0L, "KRW");
    }

    static void assertOnly(Report report, Map<Finding, Long> expected) {
        var all = new EnumMap<Finding, Long>(Finding.class);
        for (Finding finding : Finding.values()) all.put(finding, expected.getOrDefault(finding, 0L));
        assertThat(report.counts()).isEqualTo(all);
        boolean blocked = expected.entrySet().stream().anyMatch(entry -> entry.getKey().grade() == Grade.BLOCKING && entry.getValue() > 0);
        assertThat(report.verdict()).isEqualTo(blocked ? Verdict.BLOCKED : Verdict.READY);
    }

    @Test
    void normalKrwAndUsdSourcesAreReadyWithZeroFindingsAtEveryPageSize() {
        var stub = normal().mirror();
        Report first = stub.run(1);
        assertOnly(first, Map.of());
        assertThat(first.scanned()).containsExactly(Map.entry(Lane.PRODUCT, 2L), Map.entry(Lane.SKU, 4L),
            Map.entry(Lane.PRODUCT_PRICE, 4L), Map.entry(Lane.SKU_PRICE, 7L));
        assertThat(stub.run(2)).isEqualTo(first);
        assertThat(stub.run(500)).isEqualTo(first);
    }

    @Test
    void missingBaseRowsOnlyAreReadyBackfillCounts() {
        Report report = normal().run(3);
        assertOnly(report, Map.of(PRODUCT_PRICE_BASE_MISSING, 2L, SKU_PRICE_BASE_MISSING, 4L));
        assertThat(PRODUCT_PRICE_BASE_MISSING.grade()).isEqualTo(Grade.BACKFILL);
        assertThat(SKU_PRICE_BASE_MISSING.grade()).isEqualTo(Grade.BACKFILL);
    }

    @Test
    void corruptLegacyValuesAndStatusesAreClassifiedWithoutExceptions() {
        var stub = normal().mirror()
            .product(31, null, "KRW", null, null).product(32, 100L, null, null, null).product(33, 100L, "ZZZ", null, null)
            .product(34, 100L, "krw", null, null).product(35, -1L, "KRW", null, null).product(36, 100L, "KRW", 200L, null)
            .product(37, 100L, "KRW", null, "KRW").product(38, 100L, "KRW", 200L, "USD").product(39, 100L, "KRW", 100L, "KRW")
            .product(40, 100L, "KRW", 50L, "KRW")
            .sku(13, 1L, null, "KRW", ON).sku(14, 1L, 5L, null, ON).sku(15, 1L, 5L, "ZZZ", ON).sku(16, 1L, -5L, "KRW", ON)
            .sku(17, 999L, 0L, "KRW", ON).sku(18, null, 0L, "KRW", ON).sku(19, 1L, 0L, "USD", ON)
            .sku(20, 33L, 0L, "KRW", ON).sku(23, 2L, 0L, "USD", null).sku(24, 2L, 0L, "USD", "on_sale");
        assertOnly(stub.run(4), Map.of(LEGACY_PRODUCT_PRICE_INVALID, 10L, LEGACY_SKU_PRICE_INVALID, 4L, LEGACY_SKU_ORPHAN, 2L,
            LEGACY_SKU_CURRENCY_MISMATCH, 1L, LEGACY_SKU_STATUS_INVALID, 2L, SKU_PRICE_BASE_MISSING, 2L));
    }

    @Test
    void zeroRuleCountsOnlyProductsWithOnSaleBaseSkusAndExemptsStopOnlyProducts() {
        var stub = new PriceMigrationReadStub()
            .product(1, 1_000L, "KRW", null, null).sku(11, 1L, 100L, "KRW", ON).sku(12, 1L, 0L, "KRW", STOP)
            .product(2, 5L, "USD", null, null).sku(21, 2L, 5L, "USD", ON)
            .product(3, 1_000L, "KRW", null, null).sku(31, 3L, 100L, "KRW", STOP).sku(32, 3L, 200L, "KRW", "RETIRED")
            .product(4, 1_000L, "KRW", null, null).sku(41, 4L, 0L, "KRW", ON).sku(42, 4L, 100L, "KRW", ON)
            .product(5, 1_000L, "KRW", null, null)
            .mirror();
        assertOnly(stub.run(2), Map.of(LEGACY_ZERO_SKU_MISSING, 2L, LEGACY_SKU_STATUS_INVALID, 1L));
    }

    @Test
    void nonPositiveIdsAndSalePriceOverflowBlockForBaseAndExtraCurrency() {
        var stub = normal().mirror()
            .product(0, 5L, "KRW", null, null).sku(63, 0L, 0L, "KRW", ON).sku(-7, 1L, 0L, "KRW", ON)
            .productPrice(601, -9L, 1L, "KRW", null, null).skuPrice(602, -9L, 0L, "KRW").skuPrice(603, 0L, 0L, "KRW")
            .product(7, Long.MAX_VALUE - 1, "KRW", null, null).sku(71, 7L, 0L, "KRW", ON).sku(72, 7L, 5L, "KRW", STOP)
            .skuPrice(604, 22L, Long.MAX_VALUE, "KRW");
        assertOnly(stub.run(2), Map.of(LEGACY_ID_INVALID, 3L, PRODUCT_PRICE_ORPHAN, 1L, SKU_PRICE_ORPHAN, 2L,
            PRODUCT_PRICE_BASE_MISSING, 2L, SKU_PRICE_BASE_MISSING, 4L, SKU_SALE_PRICE_OVERFLOW, 2L));
    }

    @Test
    void productPriceOrphanDuplicateInvalidAndBaseDriftForKrwAndUsd() {
        var stub = normal().mirror()
            .productPrice(301, 999L, 100L, "KRW", null, null).productPrice(307, null, 100L, "KRW", null, null)
            .productPrice(308, 999L, 100L, "KRW", null, null).productPrice(309, 0L, 100L, "KRW", null, null)
            .productPrice(302, 1L, 10_000L, "KRW", 12_000L, "KRW")
            .productPrice(303, 1L, 10_000L, "ZZZ", null, null).productPrice(304, 1L, null, null, null, null)
            .product(3, 500L, "KRW", null, null).productPrice(305, 3L, 600L, "KRW", null, null)
            .product(4, 700L, "USD", 900L, "USD").productPrice(306, 4L, 700L, "USD", null, null);
        assertOnly(stub.run(2), Map.of(PRODUCT_PRICE_ORPHAN, 4L, PRODUCT_PRICE_DUPLICATE, 1L, PRODUCT_PRICE_INVALID, 2L, PRODUCT_PRICE_BASE_DRIFT, 2L));
    }

    @Test
    void skuPriceOrphanDuplicateInvalidDriftDormantAndExtraZero() {
        var stub = normal()
            .product(3, 500L, "KRW", null, null).sku(31, 3L, 0L, "KRW", ON)
            .product(4, 700L, "USD", null, null).sku(41, 4L, 0L, "USD", ON)
            .product(5, 1_000L, "KRW", null, null).sku(51, 5L, 0L, "KRW", ON).sku(52, 5L, 100L, "KRW", ON).sku(53, 5L, 0L, "KRW", STOP)
            .mirror()
            .skuPrice(401, 999L, 0L, "KRW").skuPrice(411, 999L, 0L, "KRW").skuPrice(402, 11L, 0L, "KRW").skuPrice(403, 12L, 5L, "ZZZ")
            .sku(32, 3L, 100L, "KRW", STOP).skuPrice(404, 32L, 90L, "KRW")
            .sku(42, 4L, 100L, "USD", STOP).skuPrice(405, 42L, 1L, "USD")
            .skuPrice(406, 31L, 0L, "USD").skuPrice(407, 41L, 0L, "KRW")
            .productPrice(408, 5L, 10L, "USD", null, null).skuPrice(409, 52L, 100L, "USD").skuPrice(410, 53L, 0L, "USD");
        assertOnly(stub.run(3), Map.of(SKU_PRICE_ORPHAN, 2L, SKU_PRICE_DUPLICATE, 1L, SKU_PRICE_INVALID, 1L,
            SKU_PRICE_BASE_DRIFT, 2L, SKU_PRICE_EXTRA_DORMANT, 2L, EXTRA_CURRENCY_ZERO_SKU_MISSING, 1L));
    }

    @Test
    void dormantExtraSkuPriceAloneBlocks() {
        var stub = normal().product(3, 500L, "KRW", null, null).sku(31, 3L, 0L, "KRW", ON).mirror().skuPrice(500, 31L, 0L, "USD");
        Report report = stub.run(500);
        assertThat(SKU_PRICE_EXTRA_DORMANT.grade()).isEqualTo(Grade.BLOCKING);
        assertOnly(report, Map.of(SKU_PRICE_EXTRA_DORMANT, 1L));
        assertThat(report.toString()).doesNotContain("10000", "KRW", "USD");
    }
}
