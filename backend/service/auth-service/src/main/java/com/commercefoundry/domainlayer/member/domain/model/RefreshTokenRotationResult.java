package com.commercefoundry.domainlayer.member.domain.model;

import java.time.Duration;
import java.util.Objects;

/**
 * 저장소의 회전 판정과 새 토큰에 실제로 건 TTL ({@code security-guide.md} §2-3).
 *
 * <p>TTL 은 {@code min(refresh TTL, family 상한까지 남은 시간)} 이고 저장소(Lua)가 판정과 같은 자리에서 한 번만 계산한다 —
 * 재발급 응답의 {@code refreshTokenExpiresIn} 이 이 값이라 BFF 쿠키 만료가 서버 키 TTL 과 맞는다.
 *
 * @param rotation        판정
 * @param refreshTokenTtl 새 토큰 · family 에 건 TTL. 새 토큰을 저장하지 않은 판정이면 0
 */
public record RefreshTokenRotationResult(RefreshTokenRotation rotation, Duration refreshTokenTtl) {

    public RefreshTokenRotationResult {
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(refreshTokenTtl, "refreshTokenTtl");
        if (rotation.issued() ? !refreshTokenTtl.isPositive() : !refreshTokenTtl.isZero()) {
            throw new IllegalArgumentException("새 토큰을 저장한 판정만 TTL 이 있고 0 보다 크다: " + rotation + ", " + refreshTokenTtl);
        }
    }

    /** 새 토큰을 저장하지 않은 판정(INVALID · REUSE_DETECTED · FAMILY_EXPIRED · FAMILY_CORRUPTED). */
    public static RefreshTokenRotationResult rejected(RefreshTokenRotation rotation) {
        return new RefreshTokenRotationResult(rotation, Duration.ZERO);
    }
}
