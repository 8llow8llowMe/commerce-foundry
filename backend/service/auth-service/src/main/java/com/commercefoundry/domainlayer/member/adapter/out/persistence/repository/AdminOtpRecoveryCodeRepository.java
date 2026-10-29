package com.commercefoundry.domainlayer.member.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.AdminOtpRecoveryCodeEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AdminOtpRecoveryCodeRepository extends JpaRepository<AdminOtpRecoveryCodeEntity, Long> {

    /** 아직 쓰지 않은 코드 — {@code idx_admin_otp_recovery_code_member_id} 를 탄다. 회원당 최대 8행이다. */
    List<AdminOtpRecoveryCodeEntity> findByMemberIdAndUsedAtIsNullOrderByIdAsc(Long memberId);

    /** 아직 안 쓴 코드일 때만 사용 표시 — 조건부 UPDATE 한 문장. 0행이면 다른 요청이 먼저 썼거나 그 회원의 코드가 아니다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminOtpRecoveryCodeEntity r set r.usedAt = :usedAt where r.id = :id and r.memberId = :memberId and r.usedAt is null")
    int markUsed(Long id, Long memberId, Instant usedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AdminOtpRecoveryCodeEntity r where r.memberId = :memberId")
    int deleteByMemberId(Long memberId);
}
