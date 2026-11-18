package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuPriceEntity;
import jakarta.persistence.QueryHint;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;

public interface SkuPriceRepository extends JpaRepository<SkuPriceEntity, Long> {
    List<SkuPriceEntity> findAllBySkuIdInOrderBySkuIdAscAdditionalPriceCurrencyAsc(Collection<Long> skuIds);

    /** 미연결 가격 snapshot 전용 제한 조회. count 질의와 노출·삭제·판매 상태 필터를 사용하지 않는다. */
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    List<SkuPriceEntity> findAllBySkuIdInOrderBySkuIdAscAdditionalPriceCurrencyAscIdAsc(Collection<Long> skuIds, Pageable pageable);
}
