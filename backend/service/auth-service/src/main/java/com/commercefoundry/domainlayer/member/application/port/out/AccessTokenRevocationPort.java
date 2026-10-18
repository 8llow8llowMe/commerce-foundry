package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.domainlayer.member.application.exception.MemberException;

/**
 * 로그아웃한 access 토큰의 차단 목록 쓰기 ({@code security-guide.md} §7-1). 게이트웨이가 요청마다 이 목록을 본다.
 * 키 형식과 보관 기간(TTL)은 구현이 security-core {@code RevokedAccessTokenKeys} 로 정한다 — 부르는 쪽은 jti 만 넘긴다.
 */
public interface AccessTokenRevocationPort {

    /**
     * {@code jti} 를 차단 목록에 넣는다. 이미 있으면 다시 써도 같다(멱등).
     *
     * @throws MemberException {@code TOKEN_STORE_UNAVAILABLE}(503) — 저장소에 닿지 못했다
     */
    void revoke(String tokenId);
}
