package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.application.port.out.query.SkuAggregateQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * SKU. 컬렉션 인자 조회는 빈 컬렉션이면 쿼리 없이 빈 목록이다.
 */
public interface SkuRepositoryPort {

    /** 아이디 오름차순. */
    List<Sku> findAllByProductId(long productId);

    List<Sku> findAllByIds(Collection<Long> skuIds);

    boolean existsById(long skuId);

    /** 아이디 목록 중 있는 SKU 의 아이디. 순서는 정하지 않는다. */
    List<Long> findExistingIds(Collection<Long> skuIds);

    /**
     * SKU 행을 잠그고 읽는다 — 같은 SKU 를 기준으로 판정하는 쓰기(원가 이력 추가)가 줄을 선다. 잠금 대기 상한을 넘기면
     * {@code PessimisticLockingFailureException} · {@code QueryTimeoutException} 이 그대로 올라가고, 호출한 Facade 가 재시도 가능한 409 로 바꾼다.
     */
    Optional<Sku> findByIdForUpdate(long skuId);

    /** 상품별 SKU 집계. SKU 가 없는 상품은 결과에 행이 없다. */
    List<SkuAggregateQueryResult> aggregateByProductIds(Collection<Long> productIds);
}
