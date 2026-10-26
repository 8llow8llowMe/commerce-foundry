package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import java.time.Instant;

/**
 * 관리자 access 토큰 컷오프 (#193). 발급 시각이 이 시각보다 이른 토큰은 게이트웨이가 끊는다.
 *
 * @throws MemberException {@code TOKEN_STORE_UNAVAILABLE} — Redis 에 쓰지 못했다
 */
public interface AdminAccessCutoffPort {

    void cutOff(long memberId, Instant cutoff);
}
