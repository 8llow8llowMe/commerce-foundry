package com.commercefoundry.security.issuer;

import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.token.AccessTokenFormat;
import com.commercefoundry.security.token.JwtVerificationProperties;
import com.commercefoundry.security.token.RsaKeys;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * access 토큰 발급기 (auth-service 전용). 헤더 {@code alg=RS256 · typ=at+jwt · kid=썸프린트},
 * claim {@code iss · aud · sub(memberId 문자열) · role · iat · exp · jti(UUID)} 로 서명한다 ({@code security-guide.md} 토큰 사양).
 *
 * <p>refresh 토큰은 JWT 가 아니다 — {@link OpaqueTokenGenerator} 를 쓴다.
 */
public final class JwtIssuer {

    private final JwtEncoder encoder;
    private final JwsHeader header;
    private final String issuer;
    private final String audience;
    private final Duration accessTokenTtl;
    private final Clock clock;

    private JwtIssuer(JwtEncoder encoder, JwsHeader header, String issuer, String audience, Duration accessTokenTtl, Clock clock) {
        this.encoder = encoder;
        this.header = header;
        this.issuer = issuer;
        this.audience = audience;
        this.accessTokenTtl = accessTokenTtl;
        this.clock = clock;
    }

    /**
     * 개인키를 읽고 {@code JWT_PUBLIC_KEY} 와 한 쌍인지 확인한 뒤 발급기를 만든다. 쌍이 아니면 기동하지 않는다 —
     * auth 가 다른 키로 서명하면 모든 서비스가 그 토큰을 거부한다. 키 교체 순서를 틀렸을 때 여기서 걸린다 ({@code security-guide.md} §6-3).
     */
    public static JwtIssuer create(JwtVerificationProperties verification, JwtIssuanceProperties issuance, Clock clock) {
        RSAPrivateKey privateKey = RsaKeys.loadPrivateKey(issuance.privateKey(), RsaKeys.Slot.PRIVATE_KEY);
        RSAPublicKey publicKey = RsaKeys.loadPublicKey(verification.publicKey(), RsaKeys.Slot.PUBLIC_KEY);
        if (!privateKey.getModulus().equals(publicKey.getModulus())) {
            throw new IllegalStateException(("환경변수 %s 와 %s 가 한 쌍이 아닙니다. 두 값은 같은 키 쌍에서 와야 합니다"
                + " — 키 교체 중이면 auth 에서는 둘을 함께 바꾸고, 새 키 검증만 먼저 더할 때는 %s 에 넣습니다 (security-guide.md §6-3)")
                .formatted(RsaKeys.Slot.PRIVATE_KEY.environmentVariable(), RsaKeys.Slot.PUBLIC_KEY.environmentVariable(),
                    RsaKeys.Slot.PREVIOUS_PUBLIC_KEY.environmentVariable()));
        }

        RSAKey signingKey = RsaKeys.toJwk(publicKey, privateKey);
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type(AccessTokenFormat.TYPE).keyId(signingKey.getKeyID()).build();
        return new JwtIssuer(encoder, header, verification.issuer(), verification.audience(), issuance.accessTokenTtl(), clock);
    }

    public IssuedAccessToken issueAccessToken(long memberId, MemberRole role) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId 는 양수여야 합니다");
        }
        if (role == null) {
            throw new IllegalArgumentException("role 은 필수입니다");
        }
        // JWT 의 시각은 초 단위다. 돌려주는 만료 시각이 토큰의 exp 와 같도록 먼저 자른다
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(accessTokenTtl);
        String tokenId = UUID.randomUUID().toString();

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .audience(List.of(audience))
            .subject(String.valueOf(memberId))
            .claim(MemberRole.CLAIM_NAME, role.name())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .id(tokenId)
            .build();
        String tokenValue = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(tokenValue, tokenId, expiresAt);
    }
}
