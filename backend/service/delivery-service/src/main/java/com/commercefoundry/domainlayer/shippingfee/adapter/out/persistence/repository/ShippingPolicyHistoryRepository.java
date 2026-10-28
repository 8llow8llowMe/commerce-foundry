package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyHistoryEntity;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * 배송비 정책 교체 이력 (append-only). 저장 · 범위별 최신순 조회 · 운영자별 최초 교체 시각(#357)만 있다.
 */
public interface ShippingPolicyHistoryRepository extends JpaRepository<ShippingPolicyHistoryEntity, Long> {

    /**
     * 범위 하나의 이력을 아이디 내림차순(최신순)으로 한 페이지. 목록은 {@code idx_shipping_policy_history_scope_id} 를 {@code scope} 등치 +
     * {@code id} 역순 스캔으로 타서 따로 정렬하지 않고 앞에서 자르고, 파생 count 쿼리({@code where scope = ?})도 같은 인덱스 접두를 탄다.
     * 정렬은 메서드 이름이 고정한다 — {@code pageable} 에는 정렬을 싣지 않는다.
     * 교체는 버전 검사로 직렬화되므로 id 순서가 교체 순서다.
     */
    Page<ShippingPolicyHistoryEntity> findAllByScopeOrderByIdDesc(ShippingScope scope, Pageable pageable);

    /**
     * 범위 하나에서 그 운영자 이력의 가장 이른 교체 시각. 행이 없으면 null 이다(집계라 0건이어도 한 행이 온다).
     *
     * <p>{@code idx_shipping_policy_history_scope_id} 의 {@code scope} 접두로 그 범위 행을 읽고 운영자는 걸러 낸다 — 이력은 교체 1건당 1행이라
     * 범위 전체가 작다. {@code (scope, admin_member_id, created_at)} 인덱스는 이력이 수만 건을 넘거나 이 조회가 잦아지면 둔다(#357).
     */
    @Query("""
        select min(h.createdAt) from ShippingPolicyHistoryEntity h
        where h.scope = :scope and h.adminMemberId = :adminMemberId
        """)
    Instant findFirstCreatedAt(ShippingScope scope, Long adminMemberId);
}
