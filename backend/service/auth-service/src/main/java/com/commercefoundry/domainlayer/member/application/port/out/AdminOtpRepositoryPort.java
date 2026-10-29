package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.domainlayer.member.domain.model.AdminOtpCredential;
import com.commercefoundry.domainlayer.member.domain.model.AdminOtpRecoveryCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 관리자 OTP 자격 · 복구 코드 저장 (#238). 비밀은 원문으로 주고받고, 암호화 · 키 식별자 · 키 회전은 어댑터가 한다.
 * 맞는 키가 없어 비밀을 읽지 못하면 {@code AUTH_039}(503)다.
 *
 * <p>복구 코드 교체 · 해제는 그 회원의 자격 행을 먼저 잠그고(부모 → 자식) 한다. 잠금을 대기 상한 안에 얻지 못하면 재시도 가능한 {@code AUTH_044}(409)다.
 * 둘 다 호출자 트랜잭션에 합류한다 — 등록 확인({@link #save} + {@link #replaceRecoveryCodes})은 A2 Processor 가 한 트랜잭션으로 묶는다.
 */
public interface AdminOtpRepositoryPort {

    Optional<AdminOtpCredential> findByMemberId(long memberId);

    /**
     * 확정된 자격을 새로 저장한다. 아이디는 호출자가 만든다(Snowflake) — 암호문의 AAD 에 들어가므로 저장 전에 정해져 있어야 한다.
     * 같은 회원의 자격이 이미 있으면 {@code AUTH_037}(409).
     */
    AdminOtpCredential save(AdminOtpCredential credential);

    /**
     * {@link AdminOtpCredential#accept} 가 돌려준 값({@code accepted})의 {@code lastUsedTimeStep} 을, 저장된 마지막 step 보다 늦을 때만 기록한다 —
     * 조건부 UPDATE 한 문장(대상은 {@code accepted} 의 자격 아이디 · 회원). 저장된 암호문이 이전 키로 만들어졌으면 같은 문장에서 현재 키로 다시 암호화한다.
     *
     * @return 기록했으면 {@code true}. {@code false} 면 같은 코드(또는 더 늦은 코드)를 다른 요청이 먼저 썼거나 그 자격이 없다(해제 · 재등록)
     */
    boolean recordTimeStep(AdminOtpCredential accepted, Instant now);

    /** 자격과 복구 코드를 모두 지운다 (해제 · 동료 초기화). 자격 행을 잠근 뒤 코드 → 자격 순서로 지운다. 없으면 아무것도 하지 않는다. */
    void deleteByMemberId(long memberId);

    /** 아직 쓰지 않은 복구 코드. */
    List<AdminOtpRecoveryCode> findUnusedRecoveryCodes(long memberId);

    /**
     * 아직 쓰지 않은 코드일 때만 사용 표시 — 조건부 UPDATE 한 문장.
     *
     * @return 표시했으면 {@code true}. {@code false} 면 다른 요청이 먼저 썼다
     */
    boolean markRecoveryCodeUsed(long recoveryCodeId, long memberId, Instant usedAt);

    /**
     * 그 회원의 복구 코드를 모두 지우고 {@code codeHashes}(BCrypt)로 새로 넣는다. 자격 행을 먼저 잠근다 — 자격이 없으면 {@code AUTH_036}(404)이고
     * 아무것도 쓰지 않는다(자격 없는 코드가 남지 않게). 코드 아이디는 어댑터가 만든다.
     */
    void replaceRecoveryCodes(long memberId, List<String> codeHashes);
}
