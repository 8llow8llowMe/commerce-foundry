package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingRemoteAreaEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ShippingRemoteAreaRepository extends JpaRepository<ShippingRemoteAreaEntity, Long> {

    List<ShippingRemoteAreaEntity> findAllByShippingPolicyIdOrderByPostalCodeFromAsc(Long shippingPolicyId);

    /** 정책의 구간을 한 문장으로 지운다. 파생 삭제({@code deleteBy...})는 행마다 읽고 DELETE 를 따로 보낸다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ShippingRemoteAreaEntity a where a.shippingPolicyId = :shippingPolicyId")
    int deleteAllByShippingPolicyId(Long shippingPolicyId);
}
