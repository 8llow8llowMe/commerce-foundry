package com.commercefoundry.domainlayer.member.domain.model;

import java.time.Duration;
import java.util.Objects;

/**
 * refresh 토큰 회전 정책 — 저장소가 회전을 원자적으로 판정할 때 쓰는 값 묶음 ({@code security-guide.md} §2-3).
 *
 * @param reuseGrace        이미 회전한 토큰을 형제 발급으로 봐 주는 창. 첫 회전 시각부터 잰다
 * @param maxSiblings       유예 안에서 한 토큰으로 내줄 수 있는 형제 토큰 수. 넘으면 재사용 감지로 family 를 폐기한다
 * @param ttl               새 토큰 · family 의 TTL (refresh 토큰 TTL). 상한까지 남은 시간이 더 짧으면 그 시간으로 줄인다
 * @param familyMaxLifetime family 의 절대 수명 — 최초 로그인 시각부터 잰다. 지나면 회전하지 않고 family 를 지운다(재로그인).
 *                          {@code ttl} 보다 짧으면 로그인 직후부터 TTL 이 줄어 뜻이 없어 받지 않는다
 */
public record RefreshTokenRotationPolicy(Duration reuseGrace, int maxSiblings, Duration ttl, Duration familyMaxLifetime) {

    /**
     * 회전해 줄 수 있는 최소 남은 수명. family 상한까지 이보다 적게 남았으면 회전하지 않고 만료로 본다 — 응답 {@code refreshTokenExpiresIn}(초, 버림)이
     * 0 이 되어 BFF 쿠키가 바로 지워지는 토큰을 내주지 않게. 저장소(Lua {@code 1000})가 같은 값을 쓴다.
     */
    public static final Duration MIN_REMAINING_LIFETIME = Duration.ofSeconds(1);

    /**
     * 저장된 시각(family 의 로그인 시각 · 토큰의 회전 시각)이 지금보다 미래여도 받아 주는 폭 (#143) — 인스턴스 간 시계 차이다. 이만큼까지는 경과를 0 으로 보고,
     * 넘으면 손상으로 보고 거절한다(family 를 지운다). 받아 주면 시계가 크게 앞선 인스턴스가 쓴 값이나 먼 미래 값이 절대 수명 상한을 사실상 없앤다.
     * access 토큰 만료 판정의 기본 시계 오차({@code security.jwt.clock-skew} 60초)와 같은 폭이다 — 같은 인스턴스들의 시계를 같은 크기로 본다.
     * 상한을 늘려 주는 폭이 많아야 이만큼이라 절대 수명(일 단위)에 비해 무시할 만하다. 저장소(Lua {@code now + 60000})가 같은 값을 쓴다.
     */
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    public RefreshTokenRotationPolicy {
        Objects.requireNonNull(reuseGrace, "reuseGrace");
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(familyMaxLifetime, "familyMaxLifetime");
        if (maxSiblings < 0) {
            throw new IllegalArgumentException("maxSiblings 는 0 이상이어야 한다");
        }
        if (!ttl.isPositive()) {
            throw new IllegalArgumentException("ttl 은 0 보다 커야 한다");
        }
        if (familyMaxLifetime.compareTo(ttl) < 0) {
            throw new IllegalArgumentException("familyMaxLifetime 은 ttl 이상이어야 한다");
        }
    }
}
