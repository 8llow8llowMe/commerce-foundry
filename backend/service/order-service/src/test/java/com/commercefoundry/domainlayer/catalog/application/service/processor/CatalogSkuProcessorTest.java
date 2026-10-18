package com.commercefoundry.domainlayer.catalog.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.catalog.application.port.out.query.SkuQueryResult;
import com.commercefoundry.domainlayer.catalog.domain.model.CatalogSku;
import com.commercefoundry.domainlayer.catalog.support.FakeSkuQueryPort;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CatalogSkuProcessorTest {

    private FakeSkuQueryPort skuQueryPort;
    private CatalogSkuProcessor catalogSkuProcessor;

    @BeforeEach
    void setUp() {
        skuQueryPort = new FakeSkuQueryPort();
        catalogSkuProcessor = new CatalogSkuProcessor(skuQueryPort);
    }

    private static SkuQueryResult sku(long skuId) {
        return new SkuQueryResult(skuId, skuId + 7000, "상품" + skuId, "옵션" + skuId, Money.of(10_000, CurrencyCode.KRW), false, true, 5, true);
    }

    @Test
    @DisplayName("여러 SKU 를 한 번에 읽고 조회 결과의 모든 칸을 그대로 옮긴다 — 없는 SKU 는 키가 없다")
    void findSkus() {
        skuQueryPort.put(sku(9001)).put(sku(9002));

        Map<Long, CatalogSku> skus = catalogSkuProcessor.findSkus(List.of(9001L, 9002L, 9003L));

        assertThat(skus).containsOnlyKeys(9001L, 9002L);
        assertThat(skus.get(9001L)).isEqualTo(new CatalogSku(9001, 16001, "상품9001", "옵션9001", Money.of(10_000, CurrencyCode.KRW), false, true, 5, true));
        assertThat(skuQueryPort.calls()).containsExactly(List.of(9001L, 9002L, 9003L));
    }

    @Test
    @DisplayName("빈 컬렉션이면 원격을 부르지 않는다")
    void emptyDoesNotCall() {
        assertThat(catalogSkuProcessor.findSkus(List.of())).isEmpty();
        assertThat(skuQueryPort.calls()).isEmpty();
    }

    @Test
    @DisplayName("한 건 조회 — 없으면 비어 있다")
    void findSku() {
        skuQueryPort.put(sku(9001));

        assertThat(catalogSkuProcessor.findSku(9001)).map(CatalogSku::skuId).contains(9001L);
        assertThat(catalogSkuProcessor.findSku(9002)).isEmpty();
    }
}
