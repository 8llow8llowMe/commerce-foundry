package com.commercefoundry.domainlayer.product.application.model;

import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Lane;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * B1c-B 사전검증 실행 결과. 코드별 건수와 lane별 스캔 건수만 담고 식별자·금액·통화 원문은 담지 않는다.
 * 결과는 그 실행에서 관측한 것이며 한 시점의 전체 snapshot·이행 가능 인증이 아니다(usd-pricing-guide.md §1 B1c-B).
 */
public final class PriceMigrationPreflightModel {
    private PriceMigrationPreflightModel() { }

    /** BLOCKING은 이행을 차단한다. BACKFILL은 이행이 채울 대상이며 차단하지 않는다. */
    public enum Grade { BLOCKING, BACKFILL }

    public enum Verdict { READY, BLOCKED }

    /** 한 행(또는 상품·상품×통화 단위)이 여러 코드에 동시에 집계될 수 있다. 같은 코드에는 한 번만 집계한다. */
    public enum Finding {
        /** 상품·SKU 행 id 또는 SKU의 상품 id가 0 이하(PriceBook이 거부하는 값). */
        LEGACY_ID_INVALID(Grade.BLOCKING),
        LEGACY_PRODUCT_PRICE_INVALID(Grade.BLOCKING),
        LEGACY_SKU_PRICE_INVALID(Grade.BLOCKING),
        /** SKU 판매 상태가 null이거나 판매 상태 enum 이름이 아님. zero 판정을 오염시킨다. */
        LEGACY_SKU_STATUS_INVALID(Grade.BLOCKING),
        LEGACY_SKU_ORPHAN(Grade.BLOCKING),
        LEGACY_SKU_CURRENCY_MISMATCH(Grade.BLOCKING),
        LEGACY_ZERO_SKU_MISSING(Grade.BLOCKING),
        PRODUCT_PRICE_ORPHAN(Grade.BLOCKING),
        PRODUCT_PRICE_DUPLICATE(Grade.BLOCKING),
        PRODUCT_PRICE_INVALID(Grade.BLOCKING),
        PRODUCT_PRICE_BASE_MISSING(Grade.BACKFILL),
        PRODUCT_PRICE_BASE_DRIFT(Grade.BLOCKING),
        SKU_PRICE_ORPHAN(Grade.BLOCKING),
        SKU_PRICE_DUPLICATE(Grade.BLOCKING),
        SKU_PRICE_INVALID(Grade.BLOCKING),
        SKU_PRICE_BASE_MISSING(Grade.BACKFILL),
        SKU_PRICE_BASE_DRIFT(Grade.BLOCKING),
        /** 같은 상품·통화 상품 가격행 없이 남은 추가 통화 SKU 가격. §2 "휴면 가격은 허용하지 않는다". */
        SKU_PRICE_EXTRA_DORMANT(Grade.BLOCKING),
        EXTRA_CURRENCY_ZERO_SKU_MISSING(Grade.BLOCKING),
        /** 같은 통화 상품 판매가 + SKU 추가금이 long 범위를 넘음(기준통화는 legacy, 추가 통화는 가격행 기준). */
        SKU_SALE_PRICE_OVERFLOW(Grade.BLOCKING);

        private final Grade grade;

        Finding(Grade grade) {
            this.grade = grade;
        }

        public Grade grade() { return grade; }
    }

    /** 모든 lane·코드를 0 포함으로 채운 불변 EnumMap 복사본을 보관한다. */
    public record Report(Map<Lane, Long> scanned, Map<Finding, Long> counts) {
        public Report {
            scanned = complete(Lane.class, Lane.values(), scanned);
            counts = complete(Finding.class, Finding.values(), counts);
        }

        public long count(Finding finding) { return counts.get(finding); }

        public Verdict verdict() {
            boolean blocked = counts.entrySet().stream().anyMatch(entry -> entry.getKey().grade() == Grade.BLOCKING && entry.getValue() > 0);
            return blocked ? Verdict.BLOCKED : Verdict.READY;
        }

        private static <K extends Enum<K>> Map<K, Long> complete(Class<K> type, K[] keys, Map<K, Long> source) {
            EnumMap<K, Long> copy = new EnumMap<>(type);
            for (K key : keys) {
                Long value = source.get(key);
                copy.put(key, value == null ? 0L : value);
            }
            return Collections.unmodifiableMap(copy);
        }
    }
}
