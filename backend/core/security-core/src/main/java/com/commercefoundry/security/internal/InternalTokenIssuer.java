package com.commercefoundry.security.internal;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * 서비스 토큰 발급기. 헤더 {@code alg=HS256 · typ=svc+jwt}({@code kid} 없음), claim {@code iss}(이 서비스) · {@code aud}(대상 서비스) · {@code iat} ·
 * {@code exp} · {@code jti}(UUID) 로 서명한다 ({@code security-guide.md} §9).
 *
 * <p><b>요청마다 새로 서명한다 — 캐시하지 않고, 401 을 받아도 다시 발급해 재시도하지 않는다.</b> HS256 서명은 마이크로초 단위라 캐시 이득이 없고,
 * 캐시가 없으면 만료 경계와 키 교체 직후의 낡은 토큰 문제가 생기지 않는다. {@code jti} 도 요청마다 다르다.
 * internal Feign 클라이언트는 {@code internal.client.InternalServiceTokenInterceptor} 로 이 발급기를 쓴다.
 *
 * <p>Nimbus 서명기를 직접 쓴다 — 헤더에 위 두 값 말고 아무것도(특히 비밀에서 계산한 {@code kid}) 붙지 않게. 서명기는 스레드 안전하다.
 */
public final class InternalTokenIssuer {

    private static final JWSHeader HEADER = new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType(InternalTokenFormat.TYPE)).build();

    private final JWSSigner signer;
    private final String serviceId;
    private final Duration tokenTtl;
    private final Clock clock;

    private InternalTokenIssuer(JWSSigner signer, String serviceId, Duration tokenTtl, Clock clock) {
        this.signer = signer;
        this.serviceId = serviceId;
        this.tokenTtl = tokenTtl;
        this.clock = clock;
    }

    /** 현재 비밀({@code INTERNAL_JWT_SECRET})로 서명한다. 이전 비밀은 검증에만 쓴다. */
    public static InternalTokenIssuer create(InternalTokenProperties properties, Clock clock) {
        try {
            return new InternalTokenIssuer(new MACSigner(properties.currentKey()), properties.serviceId(), properties.tokenTtl(), clock);
        } catch (JOSEException invalid) {
            // 길이는 InternalTokenProperties 가 먼저 막는다. 원인 예외 메시지에 비밀이 들어가지 않지만 싣지 않는다
            throw new IllegalStateException(InternalTokenProperties.Slot.SECRET.environmentVariable() + " 로 HS256 서명기를 만들 수 없습니다 (security-guide.md §9)");
        }
    }

    /**
     * {@code audience} 서비스를 부를 때 싣는 토큰을 새로 서명한다. 토큰 원문은 로그에 남기지 않는다.
     *
     * @param audience 대상 서비스 ID — Eureka 서비스 ID(Feign 대상 이름). 소문자 kebab 이 아니면 {@link IllegalArgumentException}
     */
    public String issue(String audience) {
        if (!InternalTokenFormat.isServiceId(audience)) {
            throw new IllegalArgumentException("서비스 토큰의 aud 는 대상 서비스 ID(소문자 kebab, 예: product-service)여야 합니다: " + audience);
        }
        // JWT 의 시각은 초 단위다
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(serviceId)
            .audience(audience)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(issuedAt.plus(tokenTtl)))
            .jwtID(UUID.randomUUID().toString())
            .build();
        SignedJWT jwt = new SignedJWT(HEADER, claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException failure) {
            throw new IllegalStateException("서비스 토큰에 서명할 수 없습니다", failure);
        }
        return jwt.serialize();
    }

    /** 보내는 토큰의 {@code iss}. */
    public String serviceId() {
        return serviceId;
    }
}
