package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyEntity;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ShippingPolicyRepository extends JpaRepository<ShippingPolicyEntity, Long> {

    Optional<ShippingPolicyEntity> findByScope(ShippingScope scope);

    /**
     * 버전이 {@code version} 일 때만 금액을 바꾸고 버전을 1 올린다. 확인과 증가가 한 문장이라 동시 교체 중 하나만 1건을 얻는다.
     *
     * <p>엔티티 변경(dirty checking)으로 하지 않는 이유 — 금액은 그대로 두고 도서산간 구간만 바꾸면 정책 행에 바뀐 컬럼이 없어
     * UPDATE 가 나가지 않고 버전도 오르지 않는다. 벌크 UPDATE 는 감사 리스너를 거치지 않아 {@code updatedAt} 을 직접 넣는다.
     *
     * @return 바꾼 행 수. 0 이면 버전 충돌(또는 정책 없음)이다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update ShippingPolicyEntity p
        set p.baseFeeAmount = :baseFeeAmount, p.baseFeeCurrency = :baseFeeCurrency,
            p.freeShippingThresholdAmount = :freeShippingThresholdAmount, p.freeShippingThresholdCurrency = :freeShippingThresholdCurrency,
            p.customsNotice = :customsNotice, p.version = p.version + 1, p.updatedAt = :updatedAt
        where p.id = :id and p.version = :version
        """)
    int updateIfVersionMatches(
        Long id, Long version, long baseFeeAmount, CurrencyCode baseFeeCurrency,
        Long freeShippingThresholdAmount, CurrencyCode freeShippingThresholdCurrency, String customsNotice, Instant updatedAt
    );
}
