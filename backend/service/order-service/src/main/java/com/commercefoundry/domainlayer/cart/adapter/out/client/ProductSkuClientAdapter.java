package com.commercefoundry.domainlayer.cart.adapter.out.client;

import com.commercefoundry.domainlayer.cart.adapter.out.client.feign.ProductSkuClient;
import com.commercefoundry.domainlayer.cart.adapter.out.client.support.ProductInternalResponseSupport;
import com.commercefoundry.domainlayer.cart.application.port.out.SkuQueryPort;
import com.commercefoundry.domainlayer.cart.application.port.out.query.SkuQueryResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductSkuClientAdapter implements SkuQueryPort {

    private final ProductSkuClient productSkuClient;
    private final ProductInternalResponseSupport productInternalResponseSupport;

    /**
     * 중복을 지우고 {@link ProductSkuClient#MAX_IDS_PER_CALL}개씩 나눠 부른다. 장바구니 50줄은 동시 담기 · 병합에서 조금 넘을 수 있는 느슨한 상한이라,
     * 넘어도 조회 · 쓰기 응답 · 삭제가 product-service 의 400 으로 막히지 않게 한다. 보통은 한 번에 끝난다.
     */
    @Override
    public List<SkuQueryResult> findSkus(Collection<Long> skuIds) {
        List<Long> distinctIds = skuIds.stream().distinct().toList();
        if (distinctIds.isEmpty()) {
            return List.of();
        }
        List<SkuQueryResult> results = new ArrayList<>();
        for (int from = 0; from < distinctIds.size(); from += ProductSkuClient.MAX_IDS_PER_CALL) {
            List<Long> chunk = distinctIds.subList(from, Math.min(from + ProductSkuClient.MAX_IDS_PER_CALL, distinctIds.size()));
            results.addAll(productInternalResponseSupport.toSkuQueryResults(productInternalResponseSupport.call(() -> productSkuClient.getSkus(chunk))));
        }
        return results;
    }
}
