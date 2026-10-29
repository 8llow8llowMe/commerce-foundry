package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipher;
import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipher.Sealed;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.AdminOtpCredentialEntity;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.AdminOtpRecoveryCodeEntity;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.AdminOtpCredentialRepository;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.AdminOtpRecoveryCodeRepository;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.AdminOtpRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.AdminOtpCredential;
import com.commercefoundry.domainlayer.member.domain.model.AdminOtpRecoveryCode;
import com.commercefoundry.persistence.util.ConstraintViolations;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 OTP 자격 · 복구 코드 저장 (#238). 비밀은 {@link AdminOtpSecretCipher} 로 암호화해 넣고 읽을 때 복호화한다 — 원문은 이 클래스 밖 DB 에 닿지 않는다.
 * 키 식별자({@code key_id})와 재암호화 판정은 여기서만 다룬다(도메인에 없다). 매핑은 칸이 적고 암호화가 끼어 있어 MapStruct 대신 여기서 직접 한다.
 *
 * <p>트랜잭션:
 * <ul>
 *   <li>{@link #save} 는 {@code saveAndFlush} 한 문장이라 {@code @Transactional} 을 달지 않는다 — 호출자 트랜잭션이 있으면 합류하고, 없으면 리포지토리 기본 트랜잭션이다.
 *       유니크 키 위반은 flush 자리에서 {@code AUTH_037} 로 바꾼다</li>
 *   <li>벌크 UPDATE · DELETE 와 잠금이 있는 메서드({@link #recordTimeStep} · {@link #deleteByMemberId} · {@link #markRecoveryCodeUsed} · {@link #replaceRecoveryCodes})는
 *       {@code @Transactional} 이다 — 벌크 문장 · {@code PESSIMISTIC_WRITE} 는 트랜잭션이 있어야 돌고, 여러 문장은 한 단위여야 한다. 호출자 트랜잭션이 있으면 합류한다</li>
 *   <li>{@link #lockByMemberId} 는 {@code @Transactional(propagation = MANDATORY)} 다 — 잠금은 호출자 트랜잭션이 끝날 때까지 쥐어야 뜻이 있어, 트랜잭션 없이 부르면
 *       {@code IllegalTransactionStateException} 으로 막는다(혼자 열린 트랜잭션이 바로 커밋하며 잠금을 풀어 버리는 실수를 막는다)</li>
 *   <li>등록 확인(자격 {@link #save} + {@link #replaceRecoveryCodes})은 이 어댑터가 묶지 않는다 — PR-A2 의 Processor 가 한 트랜잭션에서 부른다</li>
 * </ul>
 *
 * <p>잠금 순서는 부모(자격 행) → 자식(복구 코드)으로 고정한다. 잠금 대기 상한({@link AdminOtpCredentialRepository#LOCK_TIMEOUT_MILLIS})을 넘기면 409 {@code AUTH_044}.
 */
@Component
@RequiredArgsConstructor
public class AdminOtpRepositoryAdapter implements AdminOtpRepositoryPort {

    private final AdminOtpCredentialRepository credentialRepository;
    private final AdminOtpRecoveryCodeRepository recoveryCodeRepository;
    private final AdminOtpSecretCipher cipher;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Override
    public Optional<Instant> findConfirmedAt(long memberId) {
        return credentialRepository.findConfirmedAtByMemberId(memberId);
    }

    @Override
    public long countUnusedRecoveryCodes(long memberId) {
        return recoveryCodeRepository.countByMemberIdAndUsedAtIsNull(memberId);
    }

    /** 호출자 트랜잭션이 있어야 한다(MANDATORY) — 잠금은 그 트랜잭션이 끝날 때 풀린다. 혼자 열면 메서드가 끝나며 바로 풀려 뜻이 없으므로 막는다. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockByMemberId(long memberId) {
        return lockCredential(memberId).isPresent();
    }

    @Override
    public Optional<AdminOtpCredential> findByMemberId(long memberId) {
        return credentialRepository.findByMemberId(memberId).map(this::toDomain);
    }

    @Override
    public AdminOtpCredential save(AdminOtpCredential credential) {
        Sealed sealed = cipher.sealCredential(credential.memberId(), credential.id(), credential.secret());
        AdminOtpCredentialEntity entity = AdminOtpCredentialEntity.builder()
            .id(credential.id())
            .memberId(credential.memberId())
            .secretCiphertext(sealed.ciphertext())
            .keyId(sealed.keyId())
            .lastUsedTimeStep(credential.lastUsedTimeStep())
            .confirmedAt(credential.confirmedAt())
            .build();
        try {
            // 버전이 null 이라 persist(INSERT)로 간다 — 아이디 충돌은 PK 위반이다
            credentialRepository.saveAndFlush(entity);
            return credential;
        } catch (DataIntegrityViolationException conflict) {
            if (ConstraintViolations.violates(conflict, AdminOtpCredentialEntity.UK_MEMBER_ID)) {
                throw new MemberException(MemberErrorCode.OTP_ALREADY_ENROLLED);
            }
            throw conflict;
        }
    }

    /**
     * 저장된 키 식별자를 읽어 현재 키가 아니면 재암호화를 함께 시도한다 — 회전 후 로그인한 관리자부터 차례로 옮겨 간다.
     * 재암호화 UPDATE 는 "아직 그 옛 키일 때" 만 맞는다. 0행이면 그 사이 다른 인스턴스가 암호문을 바꿨거나 step 이 이미 앞선 것이라, 암호문은 두고 step 만 기록을 시도한다
     * (앞의 경우는 성공하고, 뒤의 경우는 그대로 0행이라 재사용 판정이 같다).
     */
    @Override
    @Transactional
    public boolean recordTimeStep(AdminOtpCredential accepted, Instant now) {
        Optional<String> storedKeyId = credentialRepository.findKeyIdByIdAndMemberId(accepted.id(), accepted.memberId());
        if (storedKeyId.isEmpty()) {
            return false;
        }
        if (!cipher.isCurrent(storedKeyId.get())) {
            Sealed resealed = cipher.sealCredential(accepted.memberId(), accepted.id(), accepted.secret());
            int resealedRows = credentialRepository.advanceTimeStepAndReseal(
                accepted.id(), accepted.memberId(), storedKeyId.get(), accepted.lastUsedTimeStep(), resealed.ciphertext(), resealed.keyId(), now
            );
            if (resealedRows == 1) {
                return true;
            }
        }
        return credentialRepository.advanceTimeStep(accepted.id(), accepted.memberId(), accepted.lastUsedTimeStep(), now) == 1;
    }

    /** 자격 행 잠금 → 복구 코드 삭제 → 자격 삭제. 자격이 없으면(이미 해제) 아무것도 하지 않는다 — 교체가 자격 없이 코드를 넣지 못하므로 남은 코드도 없다. */
    @Override
    @Transactional
    public void deleteByMemberId(long memberId) {
        if (lockCredential(memberId).isEmpty()) {
            return;
        }
        recoveryCodeRepository.deleteByMemberId(memberId);
        credentialRepository.deleteByMemberId(memberId);
    }

    @Override
    public List<AdminOtpRecoveryCode> findUnusedRecoveryCodes(long memberId) {
        return recoveryCodeRepository.findByMemberIdAndUsedAtIsNullOrderByIdAsc(memberId).stream()
            .map(entity -> new AdminOtpRecoveryCode(entity.getId(), entity.getMemberId(), entity.getCodeHash()))
            .toList();
    }

    @Override
    @Transactional
    public boolean markRecoveryCodeUsed(long recoveryCodeId, long memberId, Instant usedAt) {
        return recoveryCodeRepository.markUsed(recoveryCodeId, memberId, usedAt) == 1;
    }

    /**
     * 자격 행 잠금 → 지우고 한 번에 넣는다(최대 8행, {@code saveAll} 은 문장이 행마다지만 건수가 고정이라 N+1 이 아니다).
     * 잠금이 해제와 줄을 세운다 — 해제가 먼저 커밋되면 여기서 자격이 비어 {@code AUTH_036} 이고, 자격 없는 코드가 남지 않는다.
     */
    @Override
    @Transactional
    public void replaceRecoveryCodes(long memberId, List<String> codeHashes) {
        if (lockCredential(memberId).isEmpty()) {
            throw new MemberException(MemberErrorCode.OTP_NOT_ENROLLED);
        }
        recoveryCodeRepository.deleteByMemberId(memberId);
        recoveryCodeRepository.saveAllAndFlush(codeHashes.stream()
            .map(codeHash -> AdminOtpRecoveryCodeEntity.builder().id(snowflakeIdGenerator.generateId()).memberId(memberId).codeHash(codeHash).build())
            .toList());
    }

    /** 잠금 대기 상한을 넘기면(같은 관리자의 다른 OTP 쓰기가 쥐고 있다) 재시도 가능한 409 다. 트랜잭션은 예외로 롤백된다. */
    private Optional<AdminOtpCredentialEntity> lockCredential(long memberId) {
        try {
            return credentialRepository.findByMemberIdForUpdate(memberId);
        } catch (PessimisticLockingFailureException busy) {
            throw new MemberException(MemberErrorCode.OTP_BUSY, busy);
        }
    }

    private AdminOtpCredential toDomain(AdminOtpCredentialEntity entity) {
        byte[] secret = cipher.openCredential(entity.getMemberId(), entity.getId(), entity.getSecretCiphertext(), entity.getKeyId());
        return new AdminOtpCredential(entity.getId(), entity.getMemberId(), secret, entity.getLastUsedTimeStep(), entity.getConfirmedAt());
    }
}
