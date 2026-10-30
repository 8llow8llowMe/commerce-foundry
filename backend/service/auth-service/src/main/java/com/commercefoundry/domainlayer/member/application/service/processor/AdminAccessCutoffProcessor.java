package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.port.out.AccessTokenRevocationPort;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAccessCutoffPort;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 관리자 access 토큰을 끊는다 (#193). 그 계정의 발급 시각 컷오프를 쓰고, 지금 요청의 jti 가 있으면 로그아웃 차단 목록에도 넣는다.
 * 관리자 로그인 2단계(#238 PR-B)는 같은 컷오프로 이전에 받은 챌린지를 거른다({@link #issuedBeforeCutoff}).
 */
@Component
@RequiredArgsConstructor
public class AdminAccessCutoffProcessor {

    private final AdminAccessCutoffPort adminAccessCutoffPort;
    private final AccessTokenRevocationPort accessTokenRevocationPort;

    public void cutOff(long memberId, Instant cutoff) {
        adminAccessCutoffPort.cutOff(memberId, cutoff);
    }

    public void revokeCurrent(String tokenId) {
        accessTokenRevocationPort.revoke(tokenId);
    }

    /**
     * 그 시각에 받은 것이 컷오프 이전인가 — 게이트웨이가 관리자 access 를 끊는 규칙과 같다. access 의 {@code iat} 는 초 단위라 게이트웨이는 "초로 내린 발급 시각 &lt; 컷오프"
     * 면 끊는다. 컷오프는 "변경 시각을 초로 내린 값 + 1초"이므로, 변경과 같은 초(그 앞 · 뒤 모두)에 받은 것은 거절되고 다음 초부터 통과한다. 챌린지 발급 시각도 초로 내려
     * 같은 비교를 한다 — 같은 순간에 받은 access 와 챌린지의 판정이 갈리지 않게. 컷오프가 없으면 거절하지 않는다.
     *
     * <p>Redis 에 닿지 못하거나 값이 손상됐으면 {@code AUTH_013}(503) 이다(fail-closed).
     */
    public boolean issuedBeforeCutoff(long memberId, Instant issuedAt) {
        Instant issuedSecond = issuedAt.truncatedTo(ChronoUnit.SECONDS);
        return adminAccessCutoffPort.findCutoff(memberId).map(issuedSecond::isBefore).orElse(false);
    }
}
