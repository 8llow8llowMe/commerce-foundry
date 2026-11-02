package com.commercefoundry.domainlayer.aiusage.domain.model;

import java.util.Map;
import java.util.Objects;

/**
 * 설정한 모델별 단가표. 설정에 없는 모델은 항목별 최고 단가({@link AiPricedBy#MAX_FALLBACK})로 계산한다 — 모르는 모델을 싸게 잡아 한도를 넘기지 않게.
 *
 * @param version 단가표 버전 — 호출 행에 남겨 어느 가격표로 계산했는지 추적한다. 단가가 없으면 {@code null}
 * @param models  모델 ID → 단가. 비어 있을 수 있다 — 그러면 계산할 수 없다({@link #quote} 가 실패한다)
 */
public record AiPriceTable(String version, Map<String, AiPrice> models, Map<String, Map<String, AiPrice>> providers) {

    public AiPriceTable(String version, Map<String, AiPrice> models) {
        this(version, models, Map.of());
    }

    public AiPriceTable {
        models = models == null ? Map.of() : Map.copyOf(models);
        Map<String, Map<String, AiPrice>> copied = new java.util.LinkedHashMap<>();
        if (providers != null) {
            providers.forEach((provider, prices) -> {
                if (!java.util.Set.of("anthropic", "openai").contains(provider)) {
                    throw new IllegalArgumentException("Invalid pricing provider");
                }
                copied.put(provider, Map.copyOf(prices));
            });
        }
        providers = Map.copyOf(copied);
        if ((!models.isEmpty() || providers.values().stream().anyMatch(prices -> !prices.isEmpty())) && (version == null || version.isBlank())) {
            throw new IllegalArgumentException("단가가 있으면 단가표 버전이 있어야 합니다.");
        }
    }

    public boolean isEmpty() {
        return models.isEmpty() && providers.values().stream().allMatch(Map::isEmpty);
    }

    /**
     * 그 모델의 단가.
     *
     * @throws IllegalStateException 단가가 하나도 없다 — 계산할 수 없다(설정 누락. ai 를 켤 때 기동 가드가 막는다)
     */
    public AiPriceQuote quote(String model) {
        return quote(null, model);
    }

    public boolean hasExplicitPrice(String provider, String model) {
        return model != null && (providerModels(provider).containsKey(model) || models.containsKey(model));
    }

    public boolean hasPricePool(String provider) {
        return !models.isEmpty() || !providerModels(provider).isEmpty();
    }

    public AiPriceQuote quote(String provider, String model) {
        Objects.requireNonNull(model, "model");
        Map<String, AiPrice> selected = providerModels(provider);
        AiPrice configured = selected.get(model);
        if (configured == null) {
            configured = models.get(model);
        }
        if (configured != null) {
            return new AiPriceQuote(configured, AiPricedBy.CONFIG, version);
        }
        java.util.List<AiPrice> candidates = new java.util.ArrayList<>(models.values());
        candidates.addAll(selected.values());
        if (candidates.isEmpty()) {
            throw new IllegalStateException("operations.ai.pricing has no prices for the selected provider");
        }
        return new AiPriceQuote(AiPrice.maxOf(candidates), AiPricedBy.MAX_FALLBACK, version);
    }

    private Map<String, AiPrice> providerModels(String provider) {
        return provider == null ? Map.of() : providers.getOrDefault(provider, Map.of());
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
