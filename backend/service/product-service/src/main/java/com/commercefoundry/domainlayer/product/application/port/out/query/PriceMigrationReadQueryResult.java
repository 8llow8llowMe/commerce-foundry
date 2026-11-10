package com.commercefoundry.domainlayer.product.application.port.out.query;

import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Lane;
import java.time.Instant;

/** 원천 값만 보존하는 내부 조회 결과다. 도메인 판정·공개 응답 변환은 수행하지 않는다. */
public final class PriceMigrationReadQueryResult {
    private PriceMigrationReadQueryResult() { }

    public record Bounds(Long product, Long sku, Long productPrice, Long skuPrice) {
        public Long upper(Lane lane) {
            return switch (lane) {
                case PRODUCT -> product;
                case SKU -> sku;
                case PRODUCT_PRICE -> productPrice;
                case SKU_PRICE -> skuPrice;
            };
        }
        @Override public String toString() { return "Bounds[redacted]"; }
    }

    public sealed interface Row permits ProductRow, SkuRow, ProductPriceRow, SkuPriceRow {
        Long id();
    }

    public record ProductRow(Long id, Long saleAmount, String saleCurrency, Long listAmount, String listCurrency,
        String saleStatus, Boolean visible, Instant deletedAt, Long version) implements Row {
        @Override public String toString() { return "ProductRow[redacted]"; }
    }

    public record SkuRow(Long id, Long productId, Long additionalAmount, String additionalCurrency, String saleStatus) implements Row {
        @Override public String toString() { return "SkuRow[redacted]"; }
    }

    public record ProductPriceRow(Long id, Long productId, Long saleAmount, String saleCurrency, Long listAmount, String listCurrency) implements Row {
        @Override public String toString() { return "ProductPriceRow[redacted]"; }
    }

    public record SkuPriceRow(Long id, Long skuId, Long additionalAmount, String additionalCurrency) implements Row {
        @Override public String toString() { return "SkuPriceRow[redacted]"; }
    }
}
