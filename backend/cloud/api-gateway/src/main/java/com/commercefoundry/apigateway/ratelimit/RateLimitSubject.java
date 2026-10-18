package com.commercefoundry.apigateway.ratelimit;

/**
 * 버킷을 나누는 주체 ({@code security-guide.md} §7-3). 로그인한 요청은 검증된 회원 id, 비로그인 요청은 클라이언트 IP 의 HMAC 이다.
 * 값이 Redis 키에 그대로 들어가므로 개인정보 원문(이메일 · IP)을 넣지 않는다 — 만드는 길은 아래 두 팩토리뿐이다.
 *
 * @param value 키 조각. {@code member:<memberId>} 또는 {@code ip:<HMAC>}
 */
public record RateLimitSubject(String value) {

    public static RateLimitSubject member(long memberId) {
        return new RateLimitSubject("member:" + memberId);
    }

    /** @param clientIpHash {@link ClientIpHasher} 가 만든 값. IP 원문을 넘기지 않는다 */
    public static RateLimitSubject client(String clientIpHash) {
        return new RateLimitSubject("ip:" + clientIpHash);
    }
}
