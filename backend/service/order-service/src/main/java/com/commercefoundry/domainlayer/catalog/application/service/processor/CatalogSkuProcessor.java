package com.commercefoundry.domainlayer.catalog.application.service.processor;

import com.commercefoundry.domainlayer.catalog.application.port.out.SkuQueryPort;
import com.commercefoundry.domainlayer.catalog.application.port.out.query.SkuQueryResult;
import com.commercefoundry.domainlayer.catalog.domain.model.CatalogSku;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * product-service 의 현재 SKU 를 읽는다 — 원격 호출이라 트랜잭션을 걸지 않는다. 몇 개를 읽든 호출은 한 번이다 ({@code coding-conventions.md} §9-6).
 *
 * <p>다른 컨텍스트(장바구니 · 주문)의 Facade 가 주입해 쓰는 공용 조회다. 담기 가능 판정 · 수량 상한 같은 규칙은 두지 않고 부른 쪽에 둔다
 * ({@code architecture-guide.md} §3). 실패는 {@code CatalogException}({@code CATALOG_001}, 503)이고, 부른 쪽의 핸들러가 자기 코드로 번역한다.
 */
@Component
@RequiredArgsConstructor
public class CatalogSkuProcessor {

    private final SkuQueryPort skuQueryPort;

    /**
     * @return SKU 아이디 → SKU. 없는 SKU 는 키가 없다. 빈 컬렉션이면 호출하지 않는다
     */
    public Map<Long, CatalogSku> findSkus(Collection<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, CatalogSku> skus = new LinkedHashMap<>();
        for (SkuQueryResult result : skuQueryPort.findSkus(skuIds)) {
            skus.put(result.skuId(), toModel(result));
        }
        return skus;
    }

    public Optional<CatalogSku> findSku(long skuId) {
        return Optional.ofNullable(findSkus(List.of(skuId)).get(skuId));
    }

    private static CatalogSku toModel(SkuQueryResult result) {
        return new CatalogSku(
            result.skuId(), result.productId(), result.productName(), result.optionName(), result.salePrice(),
            result.purchasable(), result.soldOut(), result.maxQuantityPerOrder(), result.customOrder(), result.weightGrams(), result.hsCode(),
            result.customOrderFields());
    }
}
