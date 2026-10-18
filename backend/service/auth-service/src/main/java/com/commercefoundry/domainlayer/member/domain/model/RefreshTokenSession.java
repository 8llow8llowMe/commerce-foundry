package com.commercefoundry.domainlayer.member.domain.model;

import java.util.Objects;

/**
 * 저장소에 살아 있는 refresh 토큰 하나 — 토큰 원문이 아니라 지문으로 가리킨다.
 *
 * <p>family 는 로그인 한 번에서 회전으로 이어진 토큰 묶음이다. family 를 지우면 그 묶음의 토큰이 모두 무효가 된다
 * (재사용 감지 · 로그아웃 · 회원 없음, {@code security-guide.md} §2-3).
 *
 * @param fingerprint {@code OpaqueTokenGenerator#fingerprint} — SHA-256 base64url 43자. 원문은 어디에도 두지 않는다
 * @param memberId    토큰 주인
 * @param familyId    UUID
 */
public record RefreshTokenSession(String fingerprint, long memberId, String familyId) {

    /** 로그에 남기는 지문 길이 — 키를 그대로 찾을 수 없게 앞 몇 글자만. */
    private static final int FINGERPRINT_LOG_LENGTH = 8;

    public RefreshTokenSession {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(familyId, "familyId");
    }

    /** 로그용 지문 — 앞 {@value #FINGERPRINT_LOG_LENGTH}자 + 말줄임. */
    public String fingerprintForLog() {
        return fingerprint.substring(0, Math.min(FINGERPRINT_LOG_LENGTH, fingerprint.length())) + "…";
    }

    /** 지문 전체를 싣지 않는다. */
    @Override
    public String toString() {
        return "RefreshTokenSession[fingerprint=" + fingerprintForLog() + ", memberId=" + memberId + ", familyId=" + familyId + "]";
    }
}
