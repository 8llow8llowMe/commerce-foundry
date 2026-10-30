package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.domainlayer.member.application.model.AdminOtpChallenge;
import java.time.Duration;
import java.util.Optional;

/**
 * 관리자 로그인 2단계 챌린지 (#238 PR-B) — 비밀번호를 확인한 관리자에게 내준 1회용 챌린지. TTL 이 지나면 사라진다.
 * 부르는 쪽은 챌린지 원문이 아니라 그 해시(SHA-256, base64url 43자)만 넘긴다 — 저장소에는 원문이 닿지 않는다.
 * 값은 관리자 memberId 와 발급 시각({@link AdminOtpChallenge})이다 — 발급 시각은 비밀번호 변경 · 비활성화 컷오프와 비교한다.
 *
 * <p>모든 메서드는 저장소에 닿지 못하면 {@code AUTH_013}(503)을 던진다(fail-closed).
 */
public interface AdminOtpChallengeStorePort {

    /**
     * 챌린지를 둔다. 같은 해시가 이미 있으면 덮지 않는다.
     *
     * @return 새로 뒀으면 {@code true}. {@code false} 면 해시가 겹쳤다(부르는 쪽이 새 챌린지로 다시 시도한다)
     */
    boolean save(String challengeHash, AdminOtpChallenge challenge, Duration ttl);

    /** 챌린지 — 소비하지 않는다. 없거나 만료됐으면(값이 손상됐어도) 비어 있다. */
    Optional<AdminOtpChallenge> find(String challengeHash);

    /** 챌린지를 원자적으로 읽고 지운다(GETDEL). 동시에 부르면 한쪽만 값을 받는다. 없거나 만료됐으면(또는 다른 요청이 먼저 썼으면) 비어 있다. */
    Optional<AdminOtpChallenge> consume(String challengeHash);
}
