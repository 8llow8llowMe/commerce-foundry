package com.commercefoundry.domainlayer.product.adapter.out.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class PriceSourceMetricsAdapterTest {

    @Test
    @DisplayName("사유별 카운터에 뺀 상품 수를 더한다 — 태그는 사유 하나뿐이다")
    void countsExcludedProductsByReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            PriceSourceMetricsAdapter adapter = adapter(registry);

            adapter.recordExcludedProducts("LEGACY_DRIFT", 2);
            adapter.recordExcludedProducts("LEGACY_DRIFT", 1);
            adapter.recordExcludedProducts("INVALID_PRICE_BOOK", 1);

            Counter drift = registry.get(PriceSourceMetricsAdapter.EXCLUDED_PRODUCTS).tag(PriceSourceMetricsAdapter.REASON_TAG, "LEGACY_DRIFT").counter();
            assertThat(drift.count()).isEqualTo(3.0);
            assertThat(drift.getId().getTags()).containsExactly(Tag.of(PriceSourceMetricsAdapter.REASON_TAG, "LEGACY_DRIFT"));
            assertThat(registry.get(PriceSourceMetricsAdapter.EXCLUDED_PRODUCTS).tag(PriceSourceMetricsAdapter.REASON_TAG, "INVALID_PRICE_BOOK").counter().count())
                .isEqualTo(1.0);
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("0 이하 건수는 카운터를 만들지 않는다")
    void ignoresNonPositiveCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            adapter(registry).recordExcludedProducts("LEGACY_DRIFT", 0);

            assertThat(registry.find(PriceSourceMetricsAdapter.EXCLUDED_PRODUCTS).counter()).isNull();
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("MeterRegistry 가 없거나 지표가 실패해도 예외를 내지 않는다 — 응답을 바꾸지 않는다")
    void noRegistryOrFailureIsSilent() {
        PriceSourceMetricsAdapter withoutRegistry = new PriceSourceMetricsAdapter(new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            assertThatCode(() -> withoutRegistry.recordExcludedProducts("LEGACY_DRIFT", 1)).doesNotThrowAnyException();
            // 지표 등록 중 오류(예: 태그 값 null)가 나도 삼킨다
            assertThatCode(() -> adapter(registry).recordExcludedProducts(null, 1)).doesNotThrowAnyException();
        } finally {
            registry.close();
        }
    }

    private static PriceSourceMetricsAdapter adapter(MeterRegistry registry) {
        return new PriceSourceMetricsAdapter(new StaticListableBeanFactory(Map.of("registry", registry)).getBeanProvider(MeterRegistry.class));
    }
}
