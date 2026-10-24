package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 같은 서비스의 다른 컨텍스트(전시 어드민)가 상품을 아이디로 확인할 때 쓰는 용도별 조회. <b>숨긴 상품도 담는다 — 어드민 · 쓰기 판정 전용이다.</b>
 * 고객 응답용 상품 카드는 노출 조건이 붙은 {@code ProductListProcessor#summarizeVisible} 을 쓴다 ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙).
 */
@Component
@RequiredArgsConstructor
public class ProductLookupProcessor {

    private final ProductRepositoryPort productRepositoryPort;

    /**
     * 아이디 묶음을 한 번(in 절)에 읽는다. 없는 아이디는 결과에 없다 — 누락 판정은 호출한 쪽이 한다. 빈 컬렉션이면 쿼리 없이 빈 맵이다.
     */
    public Map<Long, Product> findAllIncludingHidden(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productRepositoryPort.findAllByIdsIncludingHidden(productIds).stream()
            .collect(Collectors.toMap(Product::id, Function.identity(), (left, right) -> left));
    }
}
