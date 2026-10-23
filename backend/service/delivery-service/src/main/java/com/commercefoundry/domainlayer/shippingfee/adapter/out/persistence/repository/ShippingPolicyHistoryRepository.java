package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyHistoryEntity;
import com.commercefoundry.shared.commerce.ShippingScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 배송비 정책 교체 이력 (append-only). 저장과 범위별 최신순 조회만 있다.
 */
public interface ShippingPolicyHistoryRepository extends JpaRepository<ShippingPolicyHistoryEntity, Long> {

    /**
     * 범위 하나의 이력을 아이디 내림차순(최신순)으로 한 페이지. 목록은 {@code idx_shipping_policy_history_scope_id} 를 {@code scope} 등치 +
     * {@code id} 역순 스캔으로 타서 따로 정렬하지 않고 앞에서 자르고, 파생 count 쿼리({@code where scope = ?})도 같은 인덱스 접두를 탄다.
     * 정렬은 메서드 이름이 고정한다 — {@code pageable} 에는 정렬을 싣지 않는다.
     */
    Page<ShippingPolicyHistoryEntity> findAllByScopeOrderByIdDesc(ShippingScope scope, Pageable pageable);
}
