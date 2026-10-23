package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyHistoryAreaEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 배송비 정책 이력의 도서산간 구간 스냅숏 (append-only).
 */
public interface ShippingPolicyHistoryAreaRepository extends JpaRepository<ShippingPolicyHistoryAreaEntity, Long> {

    /** 한 페이지 이력의 구간을 in 절 한 번으로 읽는다 ({@code idx_shipping_policy_history_area_shipping_policy_history_id}). 순서는 도메인이 정렬한다. */
    List<ShippingPolicyHistoryAreaEntity> findAllByShippingPolicyHistoryIdIn(Collection<Long> shippingPolicyHistoryIds);
}
