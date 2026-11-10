package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Finding;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Report;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Lane;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.ProductPriceRow;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.ProductRow;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Row;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.SkuPriceRow;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.SkuRow;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 원시 행을 lane 순서로 받아 발견 코드를 집계하는 단일 실행용 분류기다. 손상 값에서 예외를 던지지 않는다 —
 * Money·도메인 모델을 만들지 않고 통화·상태 문자열은 enum 이름과 정확히 같을 때만 인정한다(추정·보정 없음).
 * soft-delete·STOP·은퇴·비노출 행도 제외하지 않는다.
 * 인덱스는 상품·SKU 맵 두 개뿐이며 가격행은 부모 fact의 통화 bitmask로만 기록한다. 고아 가격행은 세기만 하고 등록하지 않는다.
 */
final class PriceMigrationPreflightClassifier {

    private static final CurrencyCode[] CURRENCIES = CurrencyCode.values();
    private static final Set<String> SKU_STATUSES = Arrays.stream(SaleStatus.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private static final class ProductFact {
        final CurrencyCode saleCurrency;
        final boolean valid;
        final long sale;
        final Long list;
        int priceMask;
        int validPriceMask;
        long[] priceSales;
        boolean onSaleBase;
        boolean zeroBase;
        int extraOnSaleMask;
        int extraZeroMask;

        ProductFact(CurrencyCode saleCurrency, boolean valid, long sale, Long list) {
            this.saleCurrency = saleCurrency;
            this.valid = valid;
            this.sale = sale;
            this.list = list;
        }
    }

    private static final class SkuFact {
        final Long productId;
        final CurrencyCode currency;
        final boolean valid;
        final long amount;
        final boolean onSale;
        /** 이 SKU의 가격행이 있는 통화. 기준통화 비트가 곧 "기준통화 가격행 있음"이다. */
        int priceMask;

        SkuFact(Long productId, CurrencyCode currency, boolean valid, long amount, boolean onSale) {
            this.productId = productId;
            this.currency = currency;
            this.valid = valid;
            this.amount = amount;
            this.onSale = onSale;
        }
    }

    private final EnumMap<Finding, Long> counts = new EnumMap<>(Finding.class);
    private final Map<Long, ProductFact> products = new HashMap<>();
    private final Map<Long, SkuFact> skus = new HashMap<>();
    private int stage;

    static Lane laneOf(Row row) {
        return switch (row) {
            case ProductRow ignored -> Lane.PRODUCT;
            case SkuRow ignored -> Lane.SKU;
            case ProductPriceRow ignored -> Lane.PRODUCT_PRICE;
            case SkuPriceRow ignored -> Lane.SKU_PRICE;
        };
    }

    /** 교차 판정이 앞 lane의 완성 인덱스를 쓰므로 lane 순서를 거스르는 입력은 호출자 오류다. */
    void accept(Row row) {
        int ordinal = laneOf(row).ordinal();
        if (ordinal < stage) {
            throw new IllegalStateException("lane 순서가 맞지 않습니다");
        }
        stage = ordinal;
        switch (row) {
            case ProductRow product -> acceptProduct(product);
            case SkuRow sku -> acceptSku(sku);
            case ProductPriceRow price -> acceptProductPrice(price);
            case SkuPriceRow price -> acceptSkuPrice(price);
        }
    }

    Report finish(Map<Lane, Long> scanned) {
        for (ProductFact product : products.values()) {
            if (product.valid && (product.priceMask & bit(product.saleCurrency)) == 0) {
                add(Finding.PRODUCT_PRICE_BASE_MISSING, 1);
            }
            if (product.onSaleBase && !product.zeroBase) {
                add(Finding.LEGACY_ZERO_SKU_MISSING, 1);
            }
            add(Finding.EXTRA_CURRENCY_ZERO_SKU_MISSING, Integer.bitCount(product.extraOnSaleMask & ~product.extraZeroMask));
        }
        for (SkuFact sku : skus.values()) {
            CurrencyCode base = usableBase(sku);
            if (base != null && (sku.priceMask & bit(base)) == 0) {
                add(Finding.SKU_PRICE_BASE_MISSING, 1);
            }
        }
        return new Report(scanned, counts);
    }

    private void acceptProduct(ProductRow row) {
        if (row.id() <= 0) {
            add(Finding.LEGACY_ID_INVALID, 1);
        }
        boolean valid = validProductValue(row.saleAmount(), row.saleCurrency(), row.listAmount(), row.listCurrency());
        if (!valid) {
            add(Finding.LEGACY_PRODUCT_PRICE_INVALID, 1);
        }
        products.put(row.id(), new ProductFact(currency(row.saleCurrency()), valid, valid ? row.saleAmount() : 0L, row.listAmount()));
    }

    private void acceptSku(SkuRow row) {
        if (row.id() <= 0 || row.productId() != null && row.productId() <= 0) {
            add(Finding.LEGACY_ID_INVALID, 1);
        }
        if (row.saleStatus() == null || !SKU_STATUSES.contains(row.saleStatus())) {
            add(Finding.LEGACY_SKU_STATUS_INVALID, 1);
        }
        CurrencyCode currency = currency(row.additionalCurrency());
        boolean valid = validAdditional(row.additionalAmount(), currency);
        if (!valid) {
            add(Finding.LEGACY_SKU_PRICE_INVALID, 1);
        }
        ProductFact product = products.get(row.productId());
        if (product == null) {
            add(Finding.LEGACY_SKU_ORPHAN, 1);
        } else if (currency != null && product.saleCurrency != null && currency != product.saleCurrency) {
            add(Finding.LEGACY_SKU_CURRENCY_MISMATCH, 1);
        }
        var fact = new SkuFact(row.productId(), currency, valid, valid ? row.additionalAmount() : 0L, SaleStatus.ON_SALE.name().equals(row.saleStatus()));
        skus.put(row.id(), fact);
        if (usableBase(fact) != null) {
            if (overflows(product.sale, fact.amount)) {
                add(Finding.SKU_SALE_PRICE_OVERFLOW, 1);
            }
            if (fact.onSale) {
                product.onSaleBase = true;
                product.zeroBase |= fact.amount == 0L;
            }
        }
    }

    private void acceptProductPrice(ProductPriceRow row) {
        boolean valid = validProductValue(row.saleAmount(), row.saleCurrency(), row.listAmount(), row.listCurrency());
        if (!valid) {
            add(Finding.PRODUCT_PRICE_INVALID, 1);
        }
        ProductFact product = products.get(row.productId());
        if (product == null) {
            add(Finding.PRODUCT_PRICE_ORPHAN, 1);
            return;
        }
        CurrencyCode currency = currency(row.saleCurrency());
        if (currency == null) {
            return;
        }
        int bit = bit(currency);
        if ((product.priceMask & bit) != 0) {
            add(Finding.PRODUCT_PRICE_DUPLICATE, 1);
        }
        product.priceMask |= bit;
        if (valid && (product.validPriceMask & bit) == 0) {
            product.validPriceMask |= bit;
            if (product.priceSales == null) {
                product.priceSales = new long[CURRENCIES.length];
            }
            product.priceSales[currency.ordinal()] = row.saleAmount();
        }
        if (valid && product.valid && currency == product.saleCurrency
            && (row.saleAmount() != product.sale || !Objects.equals(row.listAmount(), product.list))) {
            add(Finding.PRODUCT_PRICE_BASE_DRIFT, 1);
        }
    }

    private void acceptSkuPrice(SkuPriceRow row) {
        CurrencyCode currency = currency(row.additionalCurrency());
        boolean valid = validAdditional(row.additionalAmount(), currency);
        if (!valid) {
            add(Finding.SKU_PRICE_INVALID, 1);
        }
        SkuFact sku = skus.get(row.skuId());
        if (sku == null) {
            add(Finding.SKU_PRICE_ORPHAN, 1);
            return;
        }
        if (currency != null) {
            if ((sku.priceMask & bit(currency)) != 0) {
                add(Finding.SKU_PRICE_DUPLICATE, 1);
            }
            sku.priceMask |= bit(currency);
        }
        ProductFact product = products.get(sku.productId);
        if (!valid || product == null || !product.valid) {
            return;
        }
        if (currency == product.saleCurrency) {
            if (usableBase(sku) != null && row.additionalAmount() != sku.amount) {
                add(Finding.SKU_PRICE_BASE_DRIFT, 1);
            }
            return;
        }
        // 추가 통화: 같은 상품·통화 상품 가격행이 없으면 휴면 가격이다. 부모 행은 앞 lane에서 이미 모두 읽었다.
        int bit = bit(currency);
        if ((product.priceMask & bit) == 0) {
            add(Finding.SKU_PRICE_EXTRA_DORMANT, 1);
        } else if ((product.validPriceMask & bit) != 0 && overflows(product.priceSales[currency.ordinal()], row.additionalAmount())) {
            add(Finding.SKU_SALE_PRICE_OVERFLOW, 1);
        }
        if (sku.onSale) {
            product.extraOnSaleMask |= bit;
            if (row.additionalAmount() == 0L) {
                product.extraZeroMask |= bit;
            }
        }
    }

    /** 레거시 상품·SKU 가격이 모두 유효하고 통화가 같을 때만 기준통화로 교차 판정한다. 아니면 이미 차단 코드가 있다. */
    private CurrencyCode usableBase(SkuFact sku) {
        ProductFact product = products.get(sku.productId);
        return product != null && product.valid && sku.valid && sku.currency == product.saleCurrency ? product.saleCurrency : null;
    }

    private void add(Finding finding, long count) {
        if (count > 0) {
            counts.merge(finding, count, Long::sum);
        }
    }

    private static boolean overflows(long sale, long additional) {
        try {
            Math.addExact(sale, additional);
            return false;
        } catch (ArithmeticException overflow) {
            return true;
        }
    }

    private static int bit(CurrencyCode currency) {
        return 1 << currency.ordinal();
    }

    /** 판매가 0 이상·통화 지원, 정가는 양쪽 null이거나 같은 통화로 판매가보다 커야 한다. */
    private static boolean validProductValue(Long sale, String saleCurrency, Long list, String listCurrency) {
        CurrencyCode currency = currency(saleCurrency);
        if (sale == null || sale < 0 || currency == null) {
            return false;
        }
        if (list == null && listCurrency == null) {
            return true;
        }
        return list != null && list > sale && currency(listCurrency) == currency;
    }

    private static boolean validAdditional(Long amount, CurrencyCode currency) {
        return amount != null && amount >= 0 && currency != null;
    }

    private static CurrencyCode currency(String raw) {
        for (CurrencyCode code : CURRENCIES) {
            if (code.name().equals(raw)) {
                return code;
            }
        }
        return null;
    }
}
