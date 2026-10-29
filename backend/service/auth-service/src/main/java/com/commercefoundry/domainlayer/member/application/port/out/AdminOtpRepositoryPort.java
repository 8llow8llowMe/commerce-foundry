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
 *
 * <p>코드 소비({@link #recordTimeStep} · {@link #markRecoveryCodeUsed})는 같은 트랜잭션에서 자격 행 잠금({@link #lockByMemberId}) 뒤에 부른다 —
 * 소비와 그 결과(해제 · 재발급 · 로그인 2단계)가 잠금 하나로 줄 서야 해제와 겹친 소비가 자격 없는 결과를 남기지 않는다. PR-B 로그인 2단계도 이 순서를 따른다.
 */
public interface AdminOtpRepositoryPort {

    Optional<AdminOtpCredential> findByMemberId(long memberId);

    /**
     * 확정 자격의 확인 시각만 읽는다 — 등록 여부 판정 · 상태 조회용. 비밀을 복호화하지 않아 암호화 키 문제({@code AUTH_039})와 무관하다 (#238 PR-A2).
     */
    Optional<Instant> findConfirmedAt(long memberId);

    /** 아직 쓰지 않은 복구 코드 수. 자격이 없으면 0 이다. */
    long countUnusedRecoveryCodes(long memberId);

    /**
     * 그 회원의 자격 행에 쓰기 잠금을 건다 — 해제 · 복구 코드 재발급이 코드 소비보다 먼저 부른다(같은 관리자의 OTP 쓰기를 줄 세운다).
     * 호출자 트랜잭션이 있어야 한다 — 없이 부르면 어댑터가 막는다(잠금이 바로 풀려 뜻이 없다). 대기 상한을 넘기면 {@code AUTH_044}(409)다. 비밀을 복호화하지 않는다.
     *
     * @return 자격이 있어 잠갔으면 {@code true}, 없으면 {@code false}
     */
    boolean lockByMemberId(long memberId);

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
