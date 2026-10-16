package com.commercefoundry.security.internal;

import static com.commercefoundry.security.support.TestServiceTokens.CALLER;
import static com.commercefoundry.security.support.TestServiceTokens.NOW;
import static com.commercefoundry.security.support.TestServiceTokens.SERVICE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.error.SecurityErrorResolver;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.security.support.TestTokens;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

/**
 * 서비스 토큰 발급 → 검증 왕복을 Spring 컨텍스트 없이 본다. 검증은 internal 체인이 쓰는 {@link JwtAuthenticationProvider} 를 그대로 거치고,
 * 거부는 internal 체인과 같은 {@link SecurityErrorResolver#resolveServiceAuthentication} 으로 코드를 고른다.
 */
class InternalTokenVerificationTest {

    private static final Instant VERIFIED_AT = NOW.plusSeconds(10);

    // ── 발급 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("발급한 토큰은 호출 서비스를 주체로, SERVICE_<iss> 권한 하나로 인증된다")
    void issuedTokenAuthenticatesAsServicePrincipal() {
        String token = TestServiceTokens.issuer(CALLER, NOW).issue(SERVICE_ID);

        Authentication authentication = authenticate(token, VERIFIED_AT);

        assertThat(authentication.getPrincipal()).isInstanceOfSatisfying(ServicePrincipal.class, principal -> {
            assertThat(principal.serviceId()).isEqualTo(CALLER);
            assertThat(principal.tokenId()).isNotBlank();
        });
        assertThat(authentication.getName()).isEqualTo(CALLER);
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("SERVICE_order-service");
    }

    @Test
    @DisplayName("헤더는 HS256 · typ=svc+jwt 뿐이고(kid 없음), claim 은 iss · aud · iat · exp · jti 뿐이다 — exp 는 iat + 60초")
    void issuedTokenFollowsTokenSpec() {
        String token = TestServiceTokens.issuer(CALLER, NOW.plusMillis(987)).issue(SERVICE_ID);

        Jwt jwt = InternalTokenDecoderFactory.create(TestServiceTokens.properties(SERVICE_ID), clockAt(VERIFIED_AT)).decode(token);

        assertThat(jwt.getHeaders()).containsOnlyKeys("alg", "typ").containsEntry("alg", "HS256").containsEntry("typ", "svc+jwt");
        assertThat(jwt.getClaims()).containsOnlyKeys("iss", "aud", "iat", "exp", "jti");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(CALLER);
        assertThat(jwt.getAudience()).containsExactly(SERVICE_ID);
        // JWT 시각은 초 단위다 — 밀리초는 버린다
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(TestServiceTokens.TOKEN_TTL));
    }

    @Test
    @DisplayName("요청마다 새로 서명한다 — 같은 시각 · 같은 대상이어도 jti 와 토큰이 매번 다르다")
    void signsAnewForEveryRequest() {
        InternalTokenIssuer issuer = TestServiceTokens.issuer(CALLER, NOW);

        List<String> tokens = IntStream.range(0, 20).mapToObj(i -> issuer.issue(SERVICE_ID)).toList();
        Set<String> tokenIds = tokens.stream().map(token -> decode(token).getId()).collect(Collectors.toSet());

        assertThat(Set.copyOf(tokens)).hasSize(20);
        assertThat(tokenIds).hasSize(20);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "Product-Service", "product_service", "http://product-service"})
    @DisplayName("aud 가 서비스 ID 모양(소문자 kebab)이 아니면 발급하지 않는다")
    void rejectsMalformedAudience(String audience) {
        InternalTokenIssuer issuer = TestServiceTokens.issuer(CALLER, NOW);

        assertThatThrownBy(() -> issuer.issue(audience)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("aud");
    }

    // ── 키 두 슬롯 ───────────────────────────────────────────────

    @Test
    @DisplayName("이전 비밀로 서명한 토큰도 통과한다 — 키 교체 중 아직 옛 비밀로 서명하는 인스턴스")
    void tokenSignedByPreviousSecretIsAccepted() {
        String token = TestServiceTokens.sign(TestServiceTokens.PREVIOUS_SECRET, TestServiceTokens.claims(CALLER, SERVICE_ID).build());

        assertThat(authenticate(token, VERIFIED_AT).getName()).isEqualTo(CALLER);
    }

    @Test
    @DisplayName("이전 슬롯을 비우면 이전 비밀 토큰은 007 이다 — 교체 마지막 단계")
    void tokenSignedByPreviousSecretIsRejectedAfterRotation() {
        String token = TestServiceTokens.sign(TestServiceTokens.PREVIOUS_SECRET, TestServiceTokens.claims(CALLER, SERVICE_ID).build());

        assertThat(rejection(token, VERIFIED_AT, TestServiceTokens.propertiesWithoutPrevious(SERVICE_ID))).isEqualTo(SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    @Test
    @DisplayName("교체 1단계 — 이전 슬롯에 새 비밀만 받아 둔 서비스는 새 비밀 토큰을 받는다(현재 비밀로는 계속 서명한다)")
    void newSecretInPreviousSlotIsAcceptedBeforeSigningSwitches() {
        byte[] newSecret = TestServiceTokens.random(32);
        InternalTokenProperties stepOne = new InternalTokenProperties(
            SERVICE_ID, TestServiceTokens.TOKEN_TTL, TestServiceTokens.secretLine(TestServiceTokens.SECRET), TestServiceTokens.secretLine(newSecret));
        String signedWithNew = TestServiceTokens.sign(newSecret, TestServiceTokens.claims(CALLER, SERVICE_ID).build());
        String signedWithCurrent = TestServiceTokens.sign(TestServiceTokens.SECRET, TestServiceTokens.claims(CALLER, SERVICE_ID).build());

        assertThat(authenticate(signedWithNew, VERIFIED_AT, stepOne).getName()).isEqualTo(CALLER);
        assertThat(authenticate(signedWithCurrent, VERIFIED_AT, stepOne).getName()).isEqualTo(CALLER);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("이전 슬롯이 채워져 있으면 디코더를 만들 때 WARN 한 줄 — 비우라는 안내만 있고 비밀 내용은 없다")
    void warnsWhilePreviousSlotIsInUse(CapturedOutput output) {
        InternalTokenDecoderFactory.create(TestServiceTokens.properties(SERVICE_ID), clockAt(VERIFIED_AT));

        assertThat(output.getOut().lines().filter(line -> line.contains("previous secret slot is in use")))
            .singleElement().asString()
            .contains("WARN", "INTERNAL_JWT_SECRET_PREVIOUS")
            .doesNotContain(TestServiceTokens.secretLine(TestServiceTokens.PREVIOUS_SECRET), TestServiceTokens.secretLine(TestServiceTokens.SECRET));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("이전 슬롯이 비어 있으면 경고하지 않는다")
    void staysQuietWithoutPreviousSlot(CapturedOutput output) {
        InternalTokenDecoderFactory.create(TestServiceTokens.propertiesWithoutPrevious(SERVICE_ID), clockAt(VERIFIED_AT));

        assertThat(output.getOut()).doesNotContain("previous secret slot");
    }

    // ── 시각 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("만료 직후라도 시계 오차 허용(30초) 안쪽이면 통과하고, 넘으면 007 이다")
    void expiryHonorsClockSkew() {
        String token = TestServiceTokens.issuer(CALLER, NOW).issue(SERVICE_ID);
        Instant expiresAt = NOW.plus(TestServiceTokens.TOKEN_TTL);

        assertThat(authenticate(token, expiresAt.plus(InternalTokenFormat.CLOCK_SKEW)).isAuthenticated()).isTrue();
        assertThat(rejection(token, expiresAt.plus(InternalTokenFormat.CLOCK_SKEW).plusSeconds(1))).isEqualTo(SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    @Test
    @DisplayName("iat 가 시계 오차보다 더 미래면 007 이다")
    void issuedInFutureIsRejected() {
        Instant future = VERIFIED_AT.plus(InternalTokenFormat.CLOCK_SKEW).plusSeconds(1);
        String token = TestServiceTokens.sign(TestServiceTokens.SECRET, TestServiceTokens.claims(CALLER, SERVICE_ID)
            .issueTime(Date.from(future)).expirationTime(Date.from(future.plusSeconds(60))).build());

        assertThat(rejection(token, VERIFIED_AT)).isEqualTo(SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    @Test
    @DisplayName("수명(exp − iat)이 5분을 넘으면 007 이다 — 비밀을 가진 쪽이 exp 를 멀리 잡아도 받지 않는다. 5분은 통과")
    void lifetimeIsCapped() {
        String fiveMinutes = TestServiceTokens.sign(TestServiceTokens.SECRET, TestServiceTokens.claims(CALLER, SERVICE_ID)
            .expirationTime(Date.from(NOW.plus(InternalTokenFormat.MAX_TOKEN_TTL))).build());
        String tooLong = TestServiceTokens.sign(TestServiceTokens.SECRET, TestServiceTokens.claims(CALLER, SERVICE_ID)
            .expirationTime(Date.from(NOW.plus(InternalTokenFormat.MAX_TOKEN_TTL).plusSeconds(1))).build());

        assertThat(authenticate(fiveMinutes, VERIFIED_AT).isAuthenticated()).isTrue();
        assertThat(rejection(tooLong, VERIFIED_AT)).isEqualTo(SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    // ── 거부 목록 ────────────────────────────────────────────────

    static Stream<Arguments> rejectedTokens() {
        JWTClaimsSet valid = TestServiceTokens.claims(CALLER, SERVICE_ID).build();
        return Stream.of(
            Arguments.of("다른 비밀로 서명", TestServiceTokens.sign(TestServiceTokens.OTHER_SECRET, valid)),
            Arguments.of("aud 가 다른 서비스", TestServiceTokens.sign(TestServiceTokens.SECRET, TestServiceTokens.claims(CALLER, "delivery-service").build())),
            Arguments.of("aud 없음", TestServiceTokens.sign(TestServiceTokens.SECRET, TestServiceTokens.claims(CALLER, SERVICE_ID).audience((String) null).build())),
            Arguments.of("iss 없음", TestServiceTokens.sign(TestServiceTokens.SECRET, TestServiceTokens.claims(null, SERVICE_ID).build())),
            Arguments.of("iss 가 서비스 ID 모양이 아님", TestServiceTokens.sign(TestServiceTokens.SECRET, TestServiceTokens.claims("Order Service", SERVICE_ID).build())),
            Arguments.of("jti 없음", TestServiceTokens.sign(TestServiceTokens.SECRET, TestServiceTokens.claims(CALLER, SERVICE_ID).jwtID(null).build())),
            Arguments.of("exp 없음", TestServiceTokens.sign(TestServiceTokens.SECRET, TestServiceTokens.claims(CALLER, SERVICE_ID).expirationTime(null).build())),
            Arguments.of("iat 없음", TestServiceTokens.sign(TestServiceTokens.SECRET, TestServiceTokens.claims(CALLER, SERVICE_ID).issueTime(null).build())),
            Arguments.of("typ 없음", TestServiceTokens.sign(TestServiceTokens.SECRET, TestServiceTokens.header().type(null).build(), valid)),
            Arguments.of("typ=JWT", TestServiceTokens.sign(TestServiceTokens.SECRET, TestServiceTokens.header().type(JOSEObjectType.JWT).build(), valid)),
            Arguments.of("typ=at+jwt (access 토큰 타입)", TestServiceTokens.sign(TestServiceTokens.SECRET,
                TestServiceTokens.header().type(new JOSEObjectType("at+jwt")).build(), valid)),
            Arguments.of("alg=none", new PlainJWT(new PlainHeader.Builder().type(new JOSEObjectType(InternalTokenFormat.TYPE)).build(), valid).serialize()),
            Arguments.of("RS256 사용자 access 토큰", TestTokens.issuer(NOW).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue()),
            Arguments.of("형식 오류", "not-a-jwt")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedTokens")
    @DisplayName("서명 · typ · aud · iss · jti · 시각 위반과 사용자 access 토큰은 모두 401 · 007 이다")
    void rejectsWithServiceUnauthorized(String name, String token) {
        assertThat(rejection(token, VERIFIED_AT)).as(name).isEqualTo(SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    /** HS512 는 64바이트 비밀이 있어야 서명할 수 있다. 검증기도 같은 64바이트 비밀을 가졌을 때 알고리즘만으로 거부되는지 본다. */
    @Test
    @DisplayName("같은 비밀로 서명했어도 HS256 이 아니면(HS512) 007 이다 — 알고리즘은 HS256 고정")
    void otherHmacAlgorithmWithSameSecretIsRejected() {
        byte[] longSecret = TestServiceTokens.random(64);
        InternalTokenProperties properties = new InternalTokenProperties(SERVICE_ID, TestServiceTokens.TOKEN_TTL, TestServiceTokens.secretLine(longSecret), "");
        JWTClaimsSet claims = TestServiceTokens.claims(CALLER, SERVICE_ID).build();
        String hs512 = TestServiceTokens.sign(longSecret, new JWSHeader.Builder(JWSAlgorithm.HS512).type(new JOSEObjectType(InternalTokenFormat.TYPE)).build(), claims);
        String hs256 = TestServiceTokens.sign(longSecret, claims);

        assertThat(authenticate(hs256, VERIFIED_AT, properties).getName()).isEqualTo(CALLER);
        assertThat(rejection(hs512, VERIFIED_AT, properties)).isEqualTo(SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    @Test
    @DisplayName("aud 가 여러 값이어도 이 서비스가 들어 있으면 통과한다")
    void audienceContainingSelfIsAccepted() {
        String token = TestServiceTokens.sign(TestServiceTokens.SECRET,
            TestServiceTokens.claims(CALLER, SERVICE_ID).audience(List.of("delivery-service", SERVICE_ID)).build());

        assertThat(authenticate(token, VERIFIED_AT).getName()).isEqualTo(CALLER);
    }

    @Test
    @DisplayName("application/svc+jwt 도 같은 타입으로 본다 (RFC 7515)")
    void acceptsApplicationPrefixedType() {
        String token = TestServiceTokens.sign(TestServiceTokens.SECRET,
            TestServiceTokens.header().type(new JOSEObjectType("application/svc+jwt")).build(), TestServiceTokens.claims(CALLER, SERVICE_ID).build());

        assertThat(authenticate(token, VERIFIED_AT).getName()).isEqualTo(CALLER);
    }

    // ── 도우미 ────────────────────────────────────────────────────

    private static Jwt decode(String token) {
        return InternalTokenDecoderFactory.create(TestServiceTokens.propertiesWithoutPrevious(SERVICE_ID), clockAt(VERIFIED_AT)).decode(token);
    }

    private static Authentication authenticate(String token, Instant now) {
        return authenticate(token, now, TestServiceTokens.properties(SERVICE_ID));
    }

    private static Authentication authenticate(String token, Instant now, InternalTokenProperties properties) {
        return provider(now, properties).authenticate(new BearerTokenAuthenticationToken(token));
    }

    private static SecurityErrorCode rejection(String token, Instant now) {
        return rejection(token, now, TestServiceTokens.properties(SERVICE_ID));
    }

    private static SecurityErrorCode rejection(String token, Instant now, InternalTokenProperties properties) {
        AuthenticationException rejected = catchThrowableOfType(AuthenticationException.class,
            () -> provider(now, properties).authenticate(new BearerTokenAuthenticationToken(token)));
        assertThat(rejected).as("거부돼야 한다").isNotNull();
        return SecurityErrorResolver.resolveServiceAuthentication(rejected);
    }

    private static JwtAuthenticationProvider provider(Instant now, InternalTokenProperties properties) {
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(InternalTokenDecoderFactory.create(properties, clockAt(now)));
        provider.setJwtAuthenticationConverter(new ServiceJwtAuthenticationConverter());
        return provider;
    }

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
