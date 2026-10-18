package com.commercefoundry.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * refresh 토큰 회전 정책 ({@code auth.token.*}). 수명(TTL)은 발급 설정 {@code security.jwt.issuance.refresh-token-ttl} 이 정한다.
 *
 * @param refreshReuseGrace            이미 회전한 refresh 토큰이 다시 와도 탈취로 보지 않고 형제 토큰을 내주는 창 (#4 결정 6).
 *                                     BFF 의 동시 재발급 · 응답 유실 재시도를 받아 준다. FE {@code REISSUE_GRACE_MS} 와 같은 값이다. 기본 30초
 * @param refreshReuseGraceMaxSiblings 유예 안에서 한 토큰으로 내줄 수 있는 형제 토큰 수. 넘으면 재사용 감지로 family 를 폐기한다 —
 *                                     회전한 토큰을 훔친 쪽이 유예 동안 새 토큰을 마구 받아 가지 못하게. 기본 3
 */
@ConfigurationProperties(prefix = "auth.token")
public record AuthTokenProperties(
    @DefaultValue("30s") Duration refreshReuseGrace,
    @DefaultValue("3") int refreshReuseGraceMaxSiblings
) {

    /**
     * 유예 상한. 유예 안에서는 회전한 토큰을 훔친 쪽도 새 토큰을 받는다 — 길수록 재사용 감지가 무뎌진다.
     * 정상 재시도는 몇 초 안에 끝나므로 분 단위를 넘길 이유가 없다.
     */
    public static final Duration MAX_REFRESH_REUSE_GRACE = Duration.ofMinutes(2);

    /** 형제 상한의 상한. 정상 BFF 는 동시 요청 몇 개 · 재시도 한두 번이라 이보다 많을 일이 없다. */
    public static final int MAX_REFRESH_REUSE_GRACE_SIBLINGS = 10;

    public AuthTokenProperties {
        if (refreshReuseGrace == null || refreshReuseGrace.isNegative() || refreshReuseGrace.compareTo(MAX_REFRESH_REUSE_GRACE) > 0) {
            throw new IllegalStateException("auth.token.refresh-reuse-grace 는 0 이상 2분 이하여야 합니다"
                + " — 유예 안에서는 회전한 토큰을 훔친 쪽도 새 토큰을 받아 재사용 감지가 무뎌집니다");
        }
        if (refreshReuseGraceMaxSiblings < 0 || refreshReuseGraceMaxSiblings > MAX_REFRESH_REUSE_GRACE_SIBLINGS) {
            throw new IllegalStateException("auth.token.refresh-reuse-grace-max-siblings 는 0 이상 10 이하여야 합니다");
        }
    }
}
