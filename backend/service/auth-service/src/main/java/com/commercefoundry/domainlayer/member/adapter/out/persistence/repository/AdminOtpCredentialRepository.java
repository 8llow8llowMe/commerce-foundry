package com.commercefoundry.domainlayer.member.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.AdminOtpCredentialEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface AdminOtpCredentialRepository extends JpaRepository<AdminOtpCredentialEntity, Long> {

    /**
     * 잠금 대기 상한(밀리초). 넘으면 {@code PessimisticLockingFailureException} 이 나고 어댑터가 409 {@code AUTH_044} 로 바꾼다.
     * 선례(product {@code LookRepository} · {@code SkuRepository}, 2000ms)를 따른다 — 쓰기 트랜잭션이 남은 시간 안에 실패를 돌려준다.
     */
    String LOCK_TIMEOUT_MILLIS = "2000";

    /** {@code uk_admin_otp_credential_member_id} 를 탄다. */
    Optional<AdminOtpCredentialEntity> findByMemberId(Long memberId);

    /** 등록 여부 · 상태 조회 — 암호문을 읽지 않는다. {@code uk_admin_otp_credential_member_id} 를 탄다. */
    @Query("select c.confirmedAt from AdminOtpCredentialEntity c where c.memberId = :memberId")
    Optional<Instant> findConfirmedAtByMemberId(Long memberId);

    /** 사용 기록 전에 저장된 암호문의 키 식별자만 읽는다 — 재암호화가 필요한지 어댑터가 판정한다. 엔티티를 영속성 컨텍스트에 올리지 않는다. */
    @Query("select c.keyId from AdminOtpCredentialEntity c where c.id = :id and c.memberId = :memberId")
    Optional<String> findKeyIdByIdAndMemberId(Long id, Long memberId);

    /**
     * 회원의 자격 행에 쓰기 잠금을 건다 — 복구 코드 교체 · 해제를 그 회원 단위로 줄 세운다(부모 행 → 자식 순서, {@code coding-conventions.md} §9-1).
     * 자격 행이 없으면 비어 있고 잠금도 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = LOCK_TIMEOUT_MILLIS))
    @Query("select c from AdminOtpCredentialEntity c where c.memberId = :memberId")
    Optional<AdminOtpCredentialEntity> findByMemberIdForUpdate(Long memberId);

    /**
     * 받은 step 이 마지막 step 보다 늦을 때만 기록한다 — 조건부 UPDATE 한 문장. 0행이면 같은 코드(또는 이전 코드)를 다른 요청이 먼저 썼거나 그 자격이 없다
     * (해제 후 재등록이면 아이디가 달라 0행이다).
     * 벌크 UPDATE 라 감사 리스너 · {@code @Version} 이 돌지 않아 수정 시각 · 버전을 직접 올리고, 영속성 컨텍스트의 옛 값을 쓰지 않게 비운다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminOtpCredentialEntity c set c.lastUsedTimeStep = :timeStep, c.updatedAt = :now, c.version = c.version + 1"
        + " where c.id = :id and c.memberId = :memberId and c.lastUsedTimeStep < :timeStep")
    int advanceTimeStep(Long id, Long memberId, Long timeStep, Instant now);

    /**
     * {@link #advanceTimeStep} 과 같은 조건에, 저장된 키가 아직 {@code previousKeyId} 일 때만 현재 키로 다시 만든 암호문 · 키 식별자도 함께 바꾼다 (키 회전).
     * 키 조건이 없으면 그 사이 다른 인스턴스가 바꾼 암호문(다른 회전 결과)을 덮는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminOtpCredentialEntity c set c.lastUsedTimeStep = :timeStep, c.secretCiphertext = :secretCiphertext, c.keyId = :keyId,"
        + " c.updatedAt = :now, c.version = c.version + 1"
        + " where c.id = :id and c.memberId = :memberId and c.keyId = :previousKeyId and c.lastUsedTimeStep < :timeStep")
    int advanceTimeStepAndReseal(Long id, Long memberId, String previousKeyId, Long timeStep, String secretCiphertext, String keyId, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AdminOtpCredentialEntity c where c.memberId = :memberId")
    int deleteByMemberId(Long memberId);
}
