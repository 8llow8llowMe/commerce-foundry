package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneCountryEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ShippingZoneCountryRepository extends JpaRepository<ShippingZoneCountryEntity, Long> {

    List<ShippingZoneCountryEntity> findAllByShippingPolicyId(Long shippingPolicyId);

    /** 정책의 국가를 한 문장으로 지운다. 파생 삭제({@code deleteBy...})는 행마다 읽고 DELETE 를 따로 보낸다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ShippingZoneCountryEntity c where c.shippingPolicyId = :shippingPolicyId")
    int deleteAllByShippingPolicyId(Long shippingPolicyId);
}
