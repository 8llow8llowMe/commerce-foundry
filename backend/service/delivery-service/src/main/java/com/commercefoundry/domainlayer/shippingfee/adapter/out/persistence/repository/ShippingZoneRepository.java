package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ShippingZoneRepository extends JpaRepository<ShippingZoneEntity, Long> {

    List<ShippingZoneEntity> findAllByShippingPolicyIdOrderByDisplayOrderAsc(Long shippingPolicyId);

    /** 정책의 국가 그룹을 한 문장으로 지운다. 국가 · 무게 구간을 먼저 지운 뒤 부른다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ShippingZoneEntity z where z.shippingPolicyId = :shippingPolicyId")
    int deleteAllByShippingPolicyId(Long shippingPolicyId);
}
