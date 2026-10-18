package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotation;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationPolicy;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * refresh 토큰 저장 · 회전 · 폐기 ({@code security-guide.md} §2-3). 토큰 원문이 아니라 지문만 넘긴다.
 *
 * <p>모든 메서드는 저장소에 닿지 못하면 {@link MemberException}({@code TOKEN_STORE_UNAVAILABLE}, 503)을 던진다.
 * TTL · 유예 같은 정책 값은 부르는 쪽이 정해 넘긴다 — 저장소는 기록과 원자 판정만 한다.
 */
public interface RefreshTokenStorePort {

    /** 로그인 — 새 family 와 그 첫 토큰을 함께(원자) 둘 다 {@code ttl} 로 저장한다. */
    void saveNewFamily(String fingerprint, long memberId, String familyId, Duration ttl);

    /** 토큰과 그 family 가 둘 다 살아 있으면 세션, 아니면 빈 값. 읽기만 한다 — 판정은 {@link #rotate} 가 다시 한다. */
    Optional<RefreshTokenSession> findSession(String fingerprint);

    /**
     * 읽은 세션을 원자적으로 다시 확인하고 회전한다. 성공(회전 · 유예 안 형제 발급)이면 새 토큰을 같은 family 로 저장하고 family TTL 도 다시 늘린다.
     * 유예 밖 재사용 · 형제 상한 초과는 family 를 지운다.
     *
     * @param now 회전 시각 — 유예 판정의 기준이다
     */
    RefreshTokenRotation rotate(RefreshTokenSession session, String newFingerprint, Instant now, RefreshTokenRotationPolicy policy);

    /** family 를 지운다 — 그 family 의 토큰이 모두 무효가 된다. 이미 없으면 아무 일도 없다. */
    void revokeFamily(String familyId);
}
