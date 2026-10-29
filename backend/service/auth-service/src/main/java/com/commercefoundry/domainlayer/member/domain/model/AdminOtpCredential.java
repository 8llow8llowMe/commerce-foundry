package com.commercefoundry.domainlayer.member.domain.model;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * 확정된 관리자 OTP 자격 (#238). 회원당 하나다 — 미확정 등록은 DB 가 아니라 Redis 에 TTL 로 둔다.
 *
 * <p>비밀은 복호화한 원문(20바이트)이다. 저장 형식(AES-GCM 암호문)과 그 키 식별자 · 회전은 영속성 어댑터만 안다.
 *
 * <p>{@code lastUsedTimeStep} 은 마지막으로 받은 TOTP step 이다. 같은 step(같은 코드)이나 이전 step 은 다시 받지 않는다 — {@link #accept}.
 * 저장 쪽은 {@link #accept} 의 결과를 받아 같은 조건을 조건부 UPDATE 로 한 번 더 건다(동시 요청).
 *
 * <p>버전을 들고 다니지 않는다 — 이 자격은 merge 로 저장하지 않는다. 새 행은 INSERT, 이후 바뀌는 칸은 {@code last_used_time_step}(과 회전 때 암호문)뿐이고
 * 둘 다 조건부 UPDATE 한 문장이라 도메인이 든 버전은 곧 DB 와 어긋난다.
 *
 * @param id               자격 아이디 (Snowflake) — 저장 전에 상위(Processor)가 정한다. 암호문의 AAD 에 들어간다
 * @param memberId         관리자 회원 아이디
 * @param secret           TOTP 비밀 원문. 로그 · {@code toString} 에 싣지 않는다
 * @param lastUsedTimeStep 마지막으로 받은 step
 * @param confirmedAt      등록을 확인한 시각
 */
public record AdminOtpCredential(long id, long memberId, byte[] secret, long lastUsedTimeStep, Instant confirmedAt) {

    public AdminOtpCredential {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        if (secret.length == 0) {
            throw new IllegalArgumentException("otp secret must not be empty");
        }
        secret = secret.clone();
    }

    /**
     * 등록 확인으로 새 자격을 만든다. 확인에 쓴 코드의 step 을 마지막 step 으로 둔다 — 같은 코드로 바로 로그인하지 못하게.
     *
     * @param id 새 자격 아이디 (Snowflake, 호출자가 만든다)
     */
    public static AdminOtpCredential confirm(long id, long memberId, byte[] secret, long confirmedTimeStep, Instant confirmedAt) {
        return new AdminOtpCredential(id, memberId, secret, confirmedTimeStep, confirmedAt);
    }

    @Override
    public byte[] secret() {
        return secret.clone();
    }

    /** 이번 step 이 마지막으로 받은 step 보다 늦을 때만 그 step 을 기록한 새 값이다. 같거나 이르면 재사용이라 비어 있다. */
    public Optional<AdminOtpCredential> accept(long timeStep) {
        if (timeStep <= lastUsedTimeStep) {
            return Optional.empty();
        }
        return Optional.of(new AdminOtpCredential(id, memberId, secret, timeStep, confirmedAt));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AdminOtpCredential that
            && id == that.id && memberId == that.memberId && lastUsedTimeStep == that.lastUsedTimeStep
            && Arrays.equals(secret, that.secret) && confirmedAt.equals(that.confirmedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, memberId, Arrays.hashCode(secret), lastUsedTimeStep, confirmedAt);
    }

    /** 비밀은 가린다. */
    @Override
    public String toString() {
        return "AdminOtpCredential[id=" + id + ", memberId=" + memberId + ", secret=****, lastUsedTimeStep=" + lastUsedTimeStep
            + ", confirmedAt=" + confirmedAt + "]";
    }
}
