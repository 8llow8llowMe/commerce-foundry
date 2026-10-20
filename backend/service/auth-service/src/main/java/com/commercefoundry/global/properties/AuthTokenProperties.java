package com.commercefoundry.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * refresh 토큰 회전 정책 ({@code auth.token.*}). 수명(TTL)은 발급 설정 {@code security.jwt.issuance.refresh-token-ttl} 이 정한다.
 * 두 설정을 함께 봐야 하는 검사(절대 수명 ≥ refresh TTL)는 정책 빈을 만들 때 한다 ({@code AuthTokenBeansConfig}).
 *
 * @param refreshReuseGrace            이미 회전한 refresh 토큰이 다시 와도 탈취로 보지 않고 형제 토큰을 내주는 창 (#4 결정 6).
 *                                     BFF 의 동시 재발급 · 응답 유실 재시도를 받아 준다. FE {@code REISSUE_GRACE_MS} 와 같은 값이다. 기본 30초
 * @param refreshReuseGraceMaxSiblings 유예 안에서 한 토큰으로 내줄 수 있는 형제 토큰 수. 넘으면 재사용 감지로 family 를 폐기한다 —
 *                                     회전한 토큰을 훔친 쪽이 유예 동안 새 토큰을 마구 받아 가지 못하게. 기본 3
 * @param refreshFamilyMaxLifetime     로그인(family) 하나의 절대 수명 — 최초 로그인 시각부터 잰다 (#67). 회전마다 refresh TTL 이 다시 걸려도
 *                                     이 시간을 넘겨 이어지지 않는다. 기본 30일 — refresh TTL 14일의 약 두 배로, 자주 쓰는 사용자도 한 달에 한 번은 다시 로그인한다.
 *                                     0 보다 크고 90일 이하다 (#143)
 */
@ConfigurationProperties(prefix = "auth.token")
public record AuthTokenProperties(
    @DefaultValue("30s") Duration refreshReuseGrace,
    @DefaultValue("3") int refreshReuseGraceMaxSiblings,
    @DefaultValue("30d") Duration refreshFamilyMaxLifetime
) {

    /**
     * 유예 상한. 유예 안에서는 회전한 토큰을 훔친 쪽도 새 토큰을 받는다 — 길수록 재사용 감지가 무뎌진다.
     * 정상 재시도는 몇 초 안에 끝나므로 분 단위를 넘길 이유가 없다.
     */
    public static final Duration MAX_REFRESH_REUSE_GRACE = Duration.ofMinutes(2);

    /** 형제 상한의 상한. 정상 BFF 는 동시 요청 몇 개 · 재시도 한두 번이라 이보다 많을 일이 없다. */
    public static final int MAX_REFRESH_REUSE_GRACE_SIBLINGS = 10;

    /**
     * family 절대 수명의 상한 (#143). 이 시간이 탈취한 refresh 토큰을 회전으로 이어 쓸 수 있는 가장 긴 시간이다 — 재로그인 주기를 한 분기보다 늘릴 이유가 없다.
     * refresh TTL 은 이 값 이하여야 하므로({@code AuthTokenBeansConfig}) 함께 묶인다. 회전마다 밀리초로 바꿔 저장소에 넘기는 값이라
     * 터무니없이 큰 값({@code Duration.toMillis()} 오버플로)이 첫 재발급의 500 이 아니라 기동 실패로 드러나게 하는 목적도 있다.
     */
    public static final Duration MAX_REFRESH_FAMILY_MAX_LIFETIME = Duration.ofDays(90);

    public AuthTokenProperties {
        if (refreshReuseGrace == null || refreshReuseGrace.isNegative() || refreshReuseGrace.compareTo(MAX_REFRESH_REUSE_GRACE) > 0) {
            throw new IllegalStateException("auth.token.refresh-reuse-grace 는 0 이상 2분 이하여야 합니다"
                + " — 유예 안에서는 회전한 토큰을 훔친 쪽도 새 토큰을 받아 재사용 감지가 무뎌집니다");
        }
        if (refreshReuseGraceMaxSiblings < 0 || refreshReuseGraceMaxSiblings > MAX_REFRESH_REUSE_GRACE_SIBLINGS) {
            throw new IllegalStateException("auth.token.refresh-reuse-grace-max-siblings 는 0 이상 10 이하여야 합니다");
        }
        if (refreshFamilyMaxLifetime == null || !refreshFamilyMaxLifetime.isPositive() || refreshFamilyMaxLifetime.compareTo(MAX_REFRESH_FAMILY_MAX_LIFETIME) > 0) {
            throw new IllegalStateException("auth.token.refresh-family-max-lifetime 은 0 보다 크고 90일 이하여야 합니다"
                + " — 탈취한 refresh 토큰을 회전으로 이어 쓸 수 있는 가장 긴 시간입니다");
        }
    }
}
