package com.commercefoundry.domainlayer.product.application.model;

import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Verdict;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 기준통화 가격행 backfill(#20 PR-2a) 실행 모드와 결과. 결과에는 건수와 판정만 담고 식별자 · 금액 · 통화 원문은 담지 않는다.
 * 정책은 {@code usd-pricing-guide.md} §1 "B1c-C 기준통화 가격행 backfill" 이다.
 */
public final class PriceBackfillModel {
    private PriceBackfillModel() { }

    /** DRY_RUN 은 계획만 세고 쓰지 않는다. APPLY 는 상품마다 별도 TX 로 version fence 와 INSERT 를 한다. */
    public enum Mode { DRY_RUN, APPLY }

    /** 상품 한 건의 처리 결과. */
    public enum Outcome {
        /** 기준통화 가격행이 이미 모두 있다. */
        COMPLETE,
        /** 빠진 기준통화 가격행을 넣었다(APPLY). */
        FILLED,
        /** 빠진 기준통화 가격행이 있다(DRY_RUN, 쓰지 않음). */
        WOULD_FILL,
        /** version fence 또는 가격행 unique 가 동시 변경과 겹쳤다. 롤백했고 다시 실행하면 이어서 처리한다. */
        CONFLICT,
        /** 그 TX 에서 다시 읽은 legacy · 가격행이 불변식을 어겼다(drift · 통화 불일치 등). 롤백했고 보정하지 않는다. */
        INVALID,
        /** 순회 사이에 상품 행이 사라졌다. */
        NOT_FOUND
    }

    /** 상품 한 건의 결과와 넣은(또는 넣을) 행 수. */
    public record ProductResult(Outcome outcome, int productPrices, int skuPrices) {
        public ProductResult {
            Objects.requireNonNull(outcome, "outcome");
        }

        public static ProductResult of(Outcome outcome) {
            return new ProductResult(outcome, 0, 0);
        }
    }

    /** 상품 순회 집계. 넣은 행 수는 APPLY 면 커밋한 행, DRY_RUN 이면 계획한 행이다. */
    public record Tally(Map<Outcome, Long> outcomes, long productPrices, long skuPrices) {
        public Tally {
            EnumMap<Outcome, Long> copy = new EnumMap<>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                Long value = outcomes.get(outcome);
                copy.put(outcome, value == null ? 0L : value);
            }
            outcomes = Collections.unmodifiableMap(copy);
        }

        public long count(Outcome outcome) { return outcomes.get(outcome); }
    }

    /**
     * 실행 결과. 사전 점검이 BLOCKED 면 tally · after 는 null 이다(DML 없음).
     * after 는 APPLY 뒤 다시 돌린 사전 점검이고, DRY_RUN 이면 null 이다.
     */
    public record Report(Mode mode, Verdict before, Tally tally, PriceMigrationPreflightModel.Report after) {
        public Report {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(before, "before");
        }

        public boolean blocked() { return before == Verdict.BLOCKED; }
    }
}
