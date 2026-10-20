package com.commercefoundry.apigateway.ratelimit;

/**
 * 버킷을 나누는 주체 ({@code security-guide.md} §7-3). 로그인한 요청은 검증된 회원 id, 비로그인 요청은 클라이언트 IP 의 HMAC 이다.
 * 값이 Redis 키에 그대로 들어가므로 개인정보 원문(이메일 · IP)을 넣지 않는다 — 만드는 길은 아래 두 팩토리뿐이다.
 * record 로 두면 public 정식 생성자로 IP 원문 주체를 만들 수 있어 생성자를 막은 클래스로 둔다.
 */
public final class RateLimitSubject {

    private final String value;

    private RateLimitSubject(String value) {
        this.value = value;
    }

    public static RateLimitSubject member(long memberId) {
        return new RateLimitSubject("member:" + memberId);
    }

    /** @param clientIpHash {@link ClientIpHasher} 가 만든 값. IP 원문을 넘기지 않는다 */
    public static RateLimitSubject client(String clientIpHash) {
        return new RateLimitSubject("ip:" + clientIpHash);
    }

    /** @return 키 조각. {@code member:<memberId>} 또는 {@code ip:<HMAC>} */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RateLimitSubject subject && value.equals(subject.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** record 였을 때와 같은 형식이다. 로그는 {@link #value()} 를 쓴다. */
    @Override
    public String toString() {
        return "RateLimitSubject[value=" + value + "]";
    }
}
