package com.commercefoundry.apigateway.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 고객 쓰기 API 의 회원 · 클라이언트 IP 단위 요청 제한 ({@code security-guide.md} §7-3). 정책과 대상 경로는 {@code RateLimitPolicy} 가 정하고, 한도만 여기서 바꾼다.
 *
 * @param enabled    끄면 어떤 요청도 세지 않는다. 기본 켬
 * @param cartMerge  장바구니 병합(로그인 직후 한 번) — 한 번에 항목을 많이 쓰는 가장 무거운 쓰기라 따로 센다
 * @param cartWrite  그 밖의 장바구니 쓰기 (담기 · 수량 변경 · 삭제)
 * @param orderWrite 주문 쓰기 (생성 · 취소 요청 등)
 * @param orderQuote 주문서 미리보기 — 주문서에서 값을 바꿀 때마다 나가 주문 생성보다 잦으므로 따로 센다
 */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record GatewayRateLimitProperties(
    @DefaultValue("true") boolean enabled,
    Limit cartMerge,
    Limit cartWrite,
    Limit orderWrite,
    Limit orderQuote
) {

    private static final int SECONDS_PER_MINUTE = 60;

    /** yml 에 적지 않은 정책의 기본 한도. 값의 근거는 {@code security-guide.md} §7-3. */
    static final Limit DEFAULT_CART_MERGE = new Limit(6, 3);
    static final Limit DEFAULT_CART_WRITE = new Limit(60, 20);
    static final Limit DEFAULT_ORDER_WRITE = new Limit(12, 5);
    static final Limit DEFAULT_ORDER_QUOTE = new Limit(60, 20);

    public GatewayRateLimitProperties {
        cartMerge = cartMerge == null ? DEFAULT_CART_MERGE : cartMerge;
        cartWrite = cartWrite == null ? DEFAULT_CART_WRITE : cartWrite;
        orderWrite = orderWrite == null ? DEFAULT_ORDER_WRITE : orderWrite;
        orderQuote = orderQuote == null ? DEFAULT_ORDER_QUOTE : orderQuote;
    }

    /**
     * 운영자가 읽는 단위(분당 횟수 · 순간 허용 횟수)로 적는다. 토큰 버킷 값({@link #replenishRate()} 등)은 여기서 바꿔 낸다 —
     * {@code RedisRateLimiter} 는 초당 정수 보충만 알아서, 분당 60회 미만은 "1초에 토큰 1개, 요청 1번에 토큰 n개" 로 나타낸다.
     *
     * @param requestsPerMinute 꾸준히 허용하는 분당 요청 수. 60 의 약수(1·2·3·4·5·6·10·12·15·20·30) 또는 60 의 배수여야 한다
     * @param burst             한 번에 몰아서 허용하는 요청 수 (버킷 크기). 1 이상
     */
    public record Limit(int requestsPerMinute, int burst) {

        public Limit {
            if (requestsPerMinute <= 0 || burst <= 0) {
                throw new IllegalArgumentException("gateway.rate-limit 의 requests-per-minute · burst 는 1 이상이어야 합니다.");
            }
            boolean divisorOfMinute = requestsPerMinute < SECONDS_PER_MINUTE && SECONDS_PER_MINUTE % requestsPerMinute == 0;
            boolean multipleOfMinute = requestsPerMinute % SECONDS_PER_MINUTE == 0;
            if (!divisorOfMinute && !multipleOfMinute) {
                throw new IllegalArgumentException("gateway.rate-limit 의 requests-per-minute 는 60 의 약수 또는 배수여야 합니다: " + requestsPerMinute);
            }
        }

        /** 초당 버킷에 채우는 토큰 수. */
        public int replenishRate() {
            return requestsPerMinute >= SECONDS_PER_MINUTE ? requestsPerMinute / SECONDS_PER_MINUTE : 1;
        }

        /** 요청 한 번이 쓰는 토큰 수. */
        public int requestedTokens() {
            return requestsPerMinute >= SECONDS_PER_MINUTE ? 1 : SECONDS_PER_MINUTE / requestsPerMinute;
        }

        /** 버킷 크기(토큰). 요청 {@link #burst} 번을 몰아서 낼 수 있다. */
        public long burstCapacity() {
            return (long) burst * requestedTokens();
        }

        /** 거절 응답의 {@code Retry-After}(초) — 요청 한 번만큼 토큰이 다시 차는 시간. 버킷이 비었을 때의 최소 대기의 근사다. */
        public long retryAfterSeconds() {
            return (long) Math.ceil((double) requestedTokens() / replenishRate());
        }
    }
}
