package com.commercefoundry.security.token;

import static com.commercefoundry.security.support.TestTokens.MEMBER_ID;
import static com.commercefoundry.security.support.TestTokens.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.error.SecurityErrorResolver;
import com.commercefoundry.security.issuer.IssuedAccessToken;
import com.commercefoundry.security.issuer.JwtIssuer;
import com.commercefoundry.security.principal.MemberJwtAuthenticationConverter;
import com.commercefoundry.security.principal.MemberPrincipal;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestKeys;
import com.commercefoundry.security.support.TestTokens;
import com.nimbusds.jose.JOSEObjectType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

/**
 * 발급 → 검증 왕복을 Spring 컨텍스트 없이 본다. 검증은 서블릿 Resource Server 가 쓰는 {@link JwtAuthenticationProvider} 를 그대로 거친다.
 */
class AccessTokenVerificationTest {

    private static final Instant VERIFIED_AT = NOW.plus(Duration.ofMinutes(1));

    @Test
    @DisplayName("발급한 토큰은 memberId · 역할 · jti 를 가진 MemberPrincipal 로 인증된다")
    void issuedTokenAuthenticatesAsMemberPrincipal() {
        IssuedAccessToken issued = TestTokens.issuer(NOW).issueAccessToken(MEMBER_ID, MemberRole.CUSTOMER);

        Authentication authentication = authenticate(issued.tokenValue(), VERIFIED_AT);

        assertThat(authentication.getPrincipal()).isEqualTo(new MemberPrincipal(MEMBER_ID, MemberRole.CUSTOMER, issued.tokenId()));
        assertThat(authentication.getName()).isEqualTo(String.valueOf(MEMBER_ID));
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_CUSTOMER");
    }

    @Test
    @DisplayName("헤더는 RS256 · typ=at+jwt · kid=공개키 썸프린트, claim 은 iss · aud · sub · role · iat · exp · jti 이고 nbf 가 없다")
    void issuedTokenFollowsTokenSpec() {
        IssuedAccessToken issued = TestTokens.issuer(NOW).issueAccessToken(MEMBER_ID, MemberRole.ADMIN);

        Jwt jwt = AccessTokenDecoderFactory.create(TestTokens.verification(), TestTokens.clockAt(VERIFIED_AT)).decode(issued.tokenValue());

        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsEntry("typ", "at+jwt").containsEntry("kid", TestTokens.kid(TestKeys.CURRENT));
        assertThat(jwt.getClaims()).containsOnlyKeys("iss", "aud", "sub", "role", "iat", "exp", "jti");
        // iss 가 URL 이 아니라 Jwt#getIssuer()(URL 변환)는 쓰지 않는다
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(TestTokens.ISSUER);
        assertThat(jwt.getAudience()).containsExactly(TestTokens.AUDIENCE);
        assertThat(jwt.getSubject()).isEqualTo("4611686018427387904");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(jwt.getId()).isEqualTo(issued.tokenId());
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(TestTokens.ACCESS_TOKEN_TTL)).isEqualTo(issued.expiresAt());
    }

    @Test
    @DisplayName("발급 시각의 밀리초는 버린다 — 돌려준 만료 시각이 토큰의 exp 와 같아야 한다")
    void expiresAtIsTruncatedToSeconds() {
        IssuedAccessToken issued = TestTokens.issuer(NOW.plusMillis(987)).issueAccessToken(MEMBER_ID, MemberRole.CUSTOMER);

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(TestTokens.ACCESS_TOKEN_TTL));
        assertThat(issued.toString()).doesNotContain(issued.tokenValue());
    }

    @Test
    @DisplayName("만료된 토큰은 002 — 프론트가 재발급을 시도하는 유일한 신호다")
    void expiredTokenIsTokenExpired() {
        String token = TestTokens.issuer(NOW).issueAccessToken(MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
        Instant afterSkew = NOW.plus(TestTokens.ACCESS_TOKEN_TTL).plus(TestTokens.CLOCK_SKEW).plusSeconds(1);

        assertThat(rejection(token, afterSkew)).isEqualTo(SecurityErrorCode.TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("만료 직후라도 시계 오차 허용(60초) 안쪽이면 통과한다")
    void expiryWithinClockSkewIsAccepted() {
        String token = TestTokens.issuer(NOW).issueAccessToken(MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
        Instant withinSkew = NOW.plus(TestTokens.ACCESS_TOKEN_TTL).plus(TestTokens.CLOCK_SKEW);

        assertThat(authenticate(token, withinSkew).isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("이전 슬롯 키로 서명한 토큰도 통과한다 — 키 교체 중 이미 발급된 토큰")
    void tokenSignedByPreviousKeyIsAccepted() {
        String token = TestTokens.sign(TestKeys.PREVIOUS, TestTokens.claims().build());

        assertThat(authenticate(token, VERIFIED_AT).getName()).isEqualTo(String.valueOf(MEMBER_ID));
    }

    @Test
    @DisplayName("이전 슬롯을 비우면 이전 키 토큰은 003 이다 — 교체 마지막 단계")
    void tokenSignedByPreviousKeyIsRejectedAfterRotation() {
        String token = TestTokens.sign(TestKeys.PREVIOUS, TestTokens.claims().build());

        assertThat(rejection(token, VERIFIED_AT, TestTokens.verificationWithoutPrevious())).isEqualTo(SecurityErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("kid 가 없는 토큰은 등록된 RSA 키를 모두 시도한다 — 현재·이전 키 서명은 통과, 다른 키 서명은 003")
    void tokenWithoutKidTriesEveryKey() {
        String current = TestTokens.sign(TestKeys.CURRENT, TestTokens.header(TestKeys.CURRENT).keyID(null).build(), TestTokens.claims().build());
        String previous = TestTokens.sign(TestKeys.PREVIOUS, TestTokens.header(TestKeys.PREVIOUS).keyID(null).build(), TestTokens.claims().build());
        String other = TestTokens.sign(TestKeys.OTHER, TestTokens.header(TestKeys.OTHER).keyID(null).build(), TestTokens.claims().build());

        assertThat(authenticate(current, VERIFIED_AT).isAuthenticated()).isTrue();
        assertThat(authenticate(previous, VERIFIED_AT).isAuthenticated()).isTrue();
        assertThat(rejection(other, VERIFIED_AT)).isEqualTo(SecurityErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("typ 가 application/at+jwt 여도 같은 access 토큰으로 본다 (RFC 7515 §4.1.9)")
    void fullMediaTypeIsAccepted() {
        String token = TestTokens.sign(TestKeys.CURRENT, TestTokens.header(TestKeys.CURRENT).type(new JOSEObjectType("application/at+jwt")).build(), TestTokens.claims().build());

        assertThat(authenticate(token, VERIFIED_AT).isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("거부해야 하는 토큰은 모두 003 이고, 만료뿐인 토큰만 002 다 — 만료에 다른 위반이 섞이면 003")
    void rejectedTokensResolveToExpectedCodes() {
        Map<String, SecurityErrorCode> expected = Map.ofEntries(
            Map.entry("expired", SecurityErrorCode.TOKEN_EXPIRED),
            Map.entry("other-key-with-current-kid", SecurityErrorCode.TOKEN_INVALID),
            Map.entry("other-key-with-own-kid", SecurityErrorCode.TOKEN_INVALID),
            Map.entry("other-key-without-kid", SecurityErrorCode.TOKEN_INVALID),
            Map.entry("wrong-issuer", SecurityErrorCode.TOKEN_INVALID),
            Map.entry("wrong-audience", SecurityErrorCode.TOKEN_INVALID),
            Map.entry("no-typ", SecurityErrorCode.TOKEN_INVALID),
            Map.entry("typ-jwt", SecurityErrorCode.TOKEN_INVALID),
            Map.entry("sub-not-number", SecurityErrorCode.TOKEN_INVALID),
            Map.entry("role-unknown", SecurityErrorCode.TOKEN_INVALID),
            Map.entry("role-list", SecurityErrorCode.TOKEN_INVALID),
            Map.entry("no-jti", SecurityErrorCode.TOKEN_INVALID),
            Map.entry("no-exp", SecurityErrorCode.TOKEN_INVALID),
            Map.entry("alg-none", SecurityErrorCode.TOKEN_INVALID),
            Map.entry("hs256-public-key-secret", SecurityErrorCode.TOKEN_INVALID),
            Map.entry("expired-and-wrong-audience", SecurityErrorCode.TOKEN_INVALID),
            Map.entry("garbage", SecurityErrorCode.TOKEN_INVALID));

        Map<String, String> catalog = TestTokens.catalog();
        assertThat(catalog.keySet().stream().filter(name -> !name.startsWith("accepted-"))).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((name, code) -> assertThat(rejection(catalog.get(name), VERIFIED_AT)).as(name).isEqualTo(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "007", "+42", "9223372036854775808", " 42", ""})
    @DisplayName("sub 는 발급기가 쓰는 모양의 양의 정수만 받는다")
    void nonCanonicalSubjectIsRejected(String subject) {
        String token = TestTokens.sign(TestKeys.CURRENT, TestTokens.claims().subject(subject).build());

        assertThat(rejection(token, VERIFIED_AT)).isEqualTo(SecurityErrorCode.TOKEN_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "Customer", ""})
    @DisplayName("role 은 대소문자까지 MemberRole 이름과 같아야 한다 — 역할을 추측해 올리지 않는다")
    void roleMustMatchExactly(String role) {
        String token = TestTokens.sign(TestKeys.CURRENT, TestTokens.claims().claim("role", role).build());

        assertThat(rejection(token, VERIFIED_AT)).isEqualTo(SecurityErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("memberId 가 양수가 아니거나 역할이 없으면 발급하지 않는다")
    void issuerRejectsInvalidSubject() {
        JwtIssuer issuer = TestTokens.issuer(NOW);

        assertThatThrownBy(() -> issuer.issueAccessToken(0, MemberRole.CUSTOMER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issuer.issueAccessToken(MEMBER_ID, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Authentication authenticate(String token, Instant verifiedAt) {
        return provider(TestTokens.verification(), TestTokens.clockAt(verifiedAt)).authenticate(new BearerTokenAuthenticationToken(token));
    }

    private static SecurityErrorCode rejection(String token, Instant verifiedAt) {
        return rejection(token, verifiedAt, TestTokens.verification());
    }

    private static SecurityErrorCode rejection(String token, Instant verifiedAt, JwtVerificationProperties properties) {
        AuthenticationException failure = catchThrowableOfType(AuthenticationException.class,
            () -> provider(properties, TestTokens.clockAt(verifiedAt)).authenticate(new BearerTokenAuthenticationToken(token)));
        assertThat(failure).as("토큰이 거부되어야 한다").isNotNull();
        return SecurityErrorResolver.resolve(failure);
    }

    private static JwtAuthenticationProvider provider(JwtVerificationProperties properties, Clock clock) {
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(AccessTokenDecoderFactory.create(properties, clock));
        provider.setJwtAuthenticationConverter(new MemberJwtAuthenticationConverter());
        return provider;
    }
}
