package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import java.time.Instant;
import java.util.Optional;

/**
 * 관리자 access 토큰 컷오프 (#193). 발급 시각이 이 시각보다 이른 토큰은 게이트웨이가 끊는다.
 * 관리자 로그인 2단계(#238 PR-B)도 같은 값을 읽어 컷오프 이전에 받은 챌린지를 거절한다.
 *
 * <p>모든 메서드는 Redis 에 닿지 못하면 {@link MemberException} {@code TOKEN_STORE_UNAVAILABLE}(503)을 던진다.
 */
public interface AdminAccessCutoffPort {

    void cutOff(long memberId, Instant cutoff);

    /**
     * 그 관리자의 컷오프. 비밀번호 변경 · 비활성화가 없었거나 보관 TTL 이 지났으면 비어 있다.
     * 값이 숫자가 아니면(손상) 읽지 못한 것과 같이 {@code TOKEN_STORE_UNAVAILABLE} 이다 — 부르는 쪽이 fail-closed 로 거절하게.
     */
    Optional<Instant> findCutoff(long memberId);
}
