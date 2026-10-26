package com.commercefoundry.domainlayer.member.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import com.commercefoundry.security.principal.MemberRole;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<MemberEntity, Long> {

    boolean existsByEmail(String email);

    /** 로그인. 이메일은 정규화한 값이고 {@code uk_member_email} 인덱스를 탄다. */
    Optional<MemberEntity> findByEmail(String email);

    /** 어드민 회원 목록 (#6) — 역할 조건. 정렬은 {@link Pageable} 이 정한다. 역할 없는 전체 목록은 {@code findAll(Pageable)} 이다. */
    Page<MemberEntity> findAllByRole(MemberRole role, Pageable pageable);

    /** 표시 이름용 — 기본 키 {@code in} 절 한 번. 아이디 · 이름만 읽는다 (#322). */
    List<MemberNameView> findByIdIn(Collection<Long> ids);

    /**
     * 회원 행을 쓰기 잠금으로 읽는다({@code select ... for update} 계열). 트랜잭션이 끝날 때까지 같은 회원의 다른 잠금은 기다린다 —
     * 회원 단위로 줄을 세워야 하는 쓰기(배송지 개수 상한 · 기본 배송지 교체)가 쓴다. 버전은 올리지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MemberEntity> findWithLockById(Long id);

    /** 활성 관리자 전부를 쓰기 잠금으로 읽는다. 마지막 관리자 비활성화를 한 트랜잭션에서 막는다 (#193). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MemberEntity m where m.role = :role and m.deactivatedAt is null")
    List<MemberEntity> findActiveAdminsForUpdate(@Param("role") MemberRole role);

    /**
     * 읽은 버전일 때만 버전을 하나 올린다 — 조건부 UPDATE 한 문장. 바뀐 행 수가 0 이면 다른 요청이 먼저 고쳤다.
     * 벌크 UPDATE 라 감사 리스너가 돌지 않아 수정 시각을 직접 넣고, 영속성 컨텍스트의 옛 버전을 쓰지 않게 비운다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update MemberEntity m set m.version = m.version + 1, m.updatedAt = :now where m.id = :id and m.version = :version")
    int incrementVersion(Long id, Long version, Instant now);
}
