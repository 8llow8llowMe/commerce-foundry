package com.commercefoundry.domainlayer.aiusage.domain.model;

import java.util.Map;
import java.util.Objects;

/**
 * 설정한 모델별 단가표. 설정에 없는 모델은 항목별 최고 단가({@link AiPricedBy#MAX_FALLBACK})로 계산한다 — 모르는 모델을 싸게 잡아 한도를 넘기지 않게.
 *
 * @param version 단가표 버전 — 호출 행에 남겨 어느 가격표로 계산했는지 추적한다. 단가가 없으면 {@code null}
 * @param models  모델 ID → 단가. 비어 있을 수 있다 — 그러면 계산할 수 없다({@link #quote} 가 실패한다)
 */
public record AiPriceTable(String version, Map<String, AiPrice> models) {

    public AiPriceTable {
        models = models == null ? Map.of() : Map.copyOf(models);
        if (!models.isEmpty() && (version == null || version.isBlank())) {
            throw new IllegalArgumentException("단가가 있으면 단가표 버전이 있어야 합니다.");
        }
    }

    public boolean isEmpty() {
        return models.isEmpty();
    }

    /**
     * 그 모델의 단가.
     *
     * @throws IllegalStateException 단가가 하나도 없다 — 계산할 수 없다(설정 누락. ai 를 켤 때 기동 가드가 막는다)
     */
    public AiPriceQuote quote(String model) {
        Objects.requireNonNull(model, "model");
        if (models.isEmpty()) {
            throw new IllegalStateException("operations.ai.pricing.models 에 단가가 없어 비용을 계산할 수 없습니다.");
        }
        AiPrice configured = models.get(model);
        if (configured != null) {
            return new AiPriceQuote(configured, AiPricedBy.CONFIG, version);
        }
        return new AiPriceQuote(AiPrice.maxOf(models.values()), AiPricedBy.MAX_FALLBACK, version);
    }

    /**
     * @param price    계산에 쓸 단가
     * @param pricedBy 설정 단가인지 최고 단가 대체인지
     * @param version  단가표 버전
     */
    public record AiPriceQuote(AiPrice price, AiPricedBy pricedBy, String version) {

        public AiPriceQuote {
            Objects.requireNonNull(price, "price");
            Objects.requireNonNull(pricedBy, "pricedBy");
        }
    }
}
