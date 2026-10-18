package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneRateEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ShippingZoneRateRepository extends JpaRepository<ShippingZoneRateEntity, Long> {

    /** 정책의 모든 그룹 구간을 {@code in} 한 번으로 읽는다 — 그룹마다 조회하지 않는다 ({@code coding-conventions.md} §9-6). */
    List<ShippingZoneRateEntity> findAllByShippingZoneIdIn(Collection<Long> shippingZoneIds);

    /**
     * 정책의 무게 구간을 한 문장으로 지운다. 구간은 정책 아이디를 들고 있지 않아 국가 그룹을 서브쿼리로 거친다 — 국가 그룹을 지우기 전에 부른다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        delete from ShippingZoneRateEntity r
        where r.shippingZoneId in (select z.id from ShippingZoneEntity z where z.shippingPolicyId = :shippingPolicyId)
        """)
    int deleteAllByShippingPolicyId(Long shippingPolicyId);
}
