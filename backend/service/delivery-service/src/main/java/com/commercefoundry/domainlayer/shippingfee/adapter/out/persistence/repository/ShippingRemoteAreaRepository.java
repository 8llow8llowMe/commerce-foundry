package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingRemoteAreaEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ShippingRemoteAreaRepository extends JpaRepository<ShippingRemoteAreaEntity, Long> {

    List<ShippingRemoteAreaEntity> findAllByShippingPolicyIdOrderByPostalCodeFromAsc(Long shippingPolicyId);

    /**
     * 정책의 구간을 한 문장으로 지운다. 파생 삭제({@code deleteBy...})는 행마다 읽고 DELETE 를 따로 보낸다.
     *
     * <p>bulk DELETE 는 영속성 컨텍스트를 거치지 않는다 — 먼저 쌓인 쓰기를 내보내고(flush) 끝나면 컨텍스트를 비워(clear) 지운 구간 엔티티가
     * 남아 다시 flush 되거나 뒤이은 조회가 옛 구간을 돌려주지 않게 한다. 비워도 정책 교체가 깨지지 않는다 — 버전 증가는 엔티티 변경이 아니라
     * 조건부 UPDATE({@code ShippingPolicyRepository#updateIfVersionMatches})가 하고, 어댑터는 삭제 · 삽입 뒤 정책을 다시 읽으며, 이력은 새 엔티티로 넣는다 (#255).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ShippingRemoteAreaEntity a where a.shippingPolicyId = :shippingPolicyId")
    int deleteAllByShippingPolicyId(Long shippingPolicyId);
}
