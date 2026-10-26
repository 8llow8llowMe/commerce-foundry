package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.port.out.AccessTokenRevocationPort;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAccessCutoffPort;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 관리자 access 토큰을 끊는다 (#193). 그 계정의 발급 시각 컷오프를 쓰고, 지금 요청의 jti 가 있으면 로그아웃 차단 목록에도 넣는다.
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
}
