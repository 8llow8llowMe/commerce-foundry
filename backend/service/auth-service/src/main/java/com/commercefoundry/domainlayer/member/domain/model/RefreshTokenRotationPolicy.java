package com.commercefoundry.domainlayer.member.domain.model;

import java.time.Duration;
import java.util.Objects;

/**
 * refresh 토큰 회전 정책 — 저장소가 회전을 원자적으로 판정할 때 쓰는 값 묶음 ({@code security-guide.md} §2-3).
 *
 * @param reuseGrace  이미 회전한 토큰을 형제 발급으로 봐 주는 창. 첫 회전 시각부터 잰다
 * @param maxSiblings 유예 안에서 한 토큰으로 내줄 수 있는 형제 토큰 수. 넘으면 재사용 감지로 family 를 폐기한다
 * @param ttl         새 토큰 · family 의 TTL (refresh 토큰 TTL)
 */
public record RefreshTokenRotationPolicy(Duration reuseGrace, int maxSiblings, Duration ttl) {

    public RefreshTokenRotationPolicy {
        Objects.requireNonNull(reuseGrace, "reuseGrace");
        Objects.requireNonNull(ttl, "ttl");
        if (maxSiblings < 0) {
            throw new IllegalArgumentException("maxSiblings 는 0 이상이어야 한다");
        }
    }
}
