package com.commercefoundry.domainlayer.product.adapter.out.observation;

import com.commercefoundry.domainlayer.product.application.port.out.PriceSourceMetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 가격 원천 관측 지표의 Micrometer 구현. 태그는 사유 이름 하나뿐이다 — 상품 · SKU 아이디나 금액을 태그로 두지 않는다(카디널리티 · 노출).
 * MeterRegistry 가 없는 컨텍스트(슬라이스 시험)에서는 아무것도 하지 않는다. 지표 실패는 삼킨다 — 주문 금액 응답을 바꾸지 않고, 정제 WARN 로그가 남아 있다.
 */
@Component
public class PriceSourceMetricsAdapter implements PriceSourceMetricsPort {

    public static final String EXCLUDED_PRODUCTS = "commerce.product.price-source.internal-sku.excluded";
    public static final String REASON_TAG = "reason";

    private final ObjectProvider<MeterRegistry> registries;

    public PriceSourceMetricsAdapter(ObjectProvider<MeterRegistry> registries) {
        this.registries = registries;
    }

    @Override
    public void recordExcludedProducts(String reason, int count) {
        if (count <= 0) {
            return;
        }
        try {
            registries.ifAvailable(registry -> Counter.builder(EXCLUDED_PRODUCTS)
                .description("PRICE_BOOK 서비스 간 SKU 조회가 가격 원천 해석 실패로 응답에서 뺀 상품 수")
                .tag(REASON_TAG, reason)
                .register(registry)
                .increment(count));
        } catch (RuntimeException ignored) {
            // 관측 실패가 응답을 바꾸지 않는다. 사유 · 건수는 Processor 의 정제 WARN 로그에 남아 있다
        }
    }
}
