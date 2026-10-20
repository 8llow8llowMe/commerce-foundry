package com.commercefoundry.domainlayer.shippingaddress.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.shippingaddress.adapter.out.persistence.entity.ShippingAddressEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ShippingAddressRepository extends JpaRepository<ShippingAddressEntity, Long> {

    /**
     * 기본 배송지가 먼저, 그다음 최신순. null 정렬 위치가 DB 마다 달라(PostgreSQL 은 내림차순에서 null 이 앞) 파생 쿼리의
     * {@code OrderByDefaultMemberIdDesc} 대신 case 식으로 정한다.
     */
    @Query("""
        select a from ShippingAddressEntity a
        where a.memberId = :memberId
        order by case when a.defaultMemberId is null then 1 else 0 end, a.id desc
        """)
    List<ShippingAddressEntity> findAllByMemberIdOrderByDefaultFirst(Long memberId);

    Optional<ShippingAddressEntity> findByIdAndMemberId(Long id, Long memberId);

    Optional<ShippingAddressEntity> findFirstByMemberIdOrderByIdDesc(Long memberId);

    long countByMemberId(Long memberId);

    /**
     * 기존 기본 배송지를 푼다 — 조건부 UPDATE 한 문장. 새 기본을 지정하는 쓰기보다 먼저 나가야 한다(유니크 키는 즉시(행 단위) 검사 — deferrable 이 아님).
     * 쌓인 엔티티 변경을 먼저 내보내고(flushAutomatically) 영속성 컨텍스트의 옛 상태를 비운다(clearAutomatically).
     * 벌크 UPDATE 라 감사 리스너가 돌지 않아 수정 시각을 직접 넣고, 버전을 올려 옛 버전으로 읽은 전체 교체가 기본 여부를 되돌려 쓰지 않게 한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update ShippingAddressEntity a
        set a.defaultMemberId = null, a.version = a.version + 1, a.updatedAt = :now
        where a.defaultMemberId = :memberId
        """)
    int clearDefault(Long memberId, Instant now);

    /** 그 회원의 그 주소를 기본으로 — 조건부 UPDATE 한 문장. 바뀐 행 수가 0 이면 그 회원의 주소가 아니거나 지워졌다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update ShippingAddressEntity a
        set a.defaultMemberId = a.memberId, a.version = a.version + 1, a.updatedAt = :now
        where a.id = :id and a.memberId = :memberId
        """)
    int markDefault(Long id, Long memberId, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ShippingAddressEntity a where a.id = :id and a.memberId = :memberId")
    int deleteByIdAndMemberIdInBulk(Long id, Long memberId);
}
