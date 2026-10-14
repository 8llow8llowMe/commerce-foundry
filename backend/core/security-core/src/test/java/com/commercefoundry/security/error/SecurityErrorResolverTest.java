package com.commercefoundry.security.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.principal.MemberPrincipal;
import com.commercefoundry.security.principal.MemberRole;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class SecurityErrorResolverTest {

    private static final OAuth2Error EXPIRED = new OAuth2Error("SECURITY_002", "access token expired", null);
    private static final OAuth2Error INVALID = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The aud claim is not valid", null);

    @Test
    @DisplayName("토큰 없이 인증이 필요한 곳에 오면 001")
    void missingTokenIsUnauthorized() {
        assertThat(SecurityErrorResolver.resolve(new InsufficientAuthenticationException("Full authentication is required")))
            .isEqualTo(SecurityErrorCode.UNAUTHORIZED);
        assertThat(SecurityErrorResolver.resolve(new AuthenticationCredentialsNotFoundException("no context")))
            .isEqualTo(SecurityErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("검증 위반이 만료 하나뿐이면 002 — Spring 이 감싼 원인 사슬에서 찾는다")
    void onlyExpiryIsTokenExpired() {
        assertThat(SecurityErrorResolver.resolve(bearerFailure(new JwtValidationException("expired", List.of(EXPIRED)))))
            .isEqualTo(SecurityErrorCode.TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("만료와 다른 위반이 섞이면 003 — 재발급으로 풀리지 않는 토큰이다")
    void expiryMixedWithOtherViolationIsTokenInvalid() {
        assertThat(SecurityErrorResolver.resolve(bearerFailure(new JwtValidationException("expired", List.of(EXPIRED, INVALID)))))
            .isEqualTo(SecurityErrorCode.TOKEN_INVALID);
        assertThat(SecurityErrorResolver.resolve(bearerFailure(new JwtValidationException("aud", List.of(INVALID)))))
            .isEqualTo(SecurityErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("메시지에 'expired' 가 있어도 오류 코드가 아니면 만료로 보지 않는다 — 문자열 매칭을 하지 않는다")
    void doesNotMatchMessageText() {
        assertThat(SecurityErrorResolver.resolve(bearerFailure(new BadJwtException("Expired JWT"))))
            .isEqualTo(SecurityErrorCode.TOKEN_INVALID);
        assertThat(SecurityErrorResolver.resolve(new InvalidBearerTokenException("Jwt expired at 2026-10-14T03:00:00Z")))
            .isEqualTo(SecurityErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("OAuth2Error 코드에 SECURITY_xxx 를 실은 예외는 그 코드로 답한다 — #4 의 로그아웃 토큰(004) · 저장소 장애(006)")
    void declaredSecurityCodeWins() {
        assertThat(SecurityErrorResolver.resolve(new OAuth2AuthenticationException(new OAuth2Error("SECURITY_004"))))
            .isEqualTo(SecurityErrorCode.TOKEN_REVOKED);
        assertThat(SecurityErrorResolver.resolve(new InvalidBearerTokenException("wrapped", new OAuth2AuthenticationException(new OAuth2Error("SECURITY_006")))))
            .isEqualTo(SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SECURITY_001", "SECURITY_002", "SECURITY_003", "SECURITY_005"})
    @DisplayName("예외에 실린 코드는 004 · 006 만 따른다 — 만료가 아닌 토큰이 002 를 실어 재발급을 유도하지 못한다")
    void ignoresNonDeclarableCodes(String code) {
        assertThat(SecurityErrorResolver.resolve(new OAuth2AuthenticationException(new OAuth2Error(code))))
            .isEqualTo(SecurityErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("validator 가 SECURITY_004 를 남겨도 JwtValidationException 안이라 003 이다 — #4 는 디코더 래퍼에서 던진다")
    void revokedCodeFromValidatorIsTokenInvalid() {
        OAuth2Error revoked = new OAuth2Error("SECURITY_004", "revoked", null);

        assertThat(SecurityErrorResolver.resolve(bearerFailure(new JwtValidationException("revoked", List.of(revoked)))))
            .isEqualTo(SecurityErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("인증 처리 중 시스템 오류는 006(503) — 토큰 잘못으로 보고 로그아웃시키지 않는다")
    void authenticationServiceFailureIsUnavailable() {
        assertThat(SecurityErrorResolver.resolve(new AuthenticationServiceException("key source down")))
            .isEqualTo(SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE);
    }

    @Test
    @DisplayName("권한 거부는 익명이면 001, 로그인했으면 005 다")
    void accessDeniedDependsOnAuthentication() {
        var anonymous = new AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        var member = UsernamePasswordAuthenticationToken.authenticated(
            new MemberPrincipal(42, MemberRole.CUSTOMER, "jti"), null, AuthorityUtils.createAuthorityList("ROLE_CUSTOMER"));

        assertThat(SecurityErrorResolver.resolveAccessDenied(null)).isEqualTo(SecurityErrorCode.UNAUTHORIZED);
        assertThat(SecurityErrorResolver.resolveAccessDenied(anonymous)).isEqualTo(SecurityErrorCode.UNAUTHORIZED);
        assertThat(SecurityErrorResolver.resolveAccessDenied(member)).isEqualTo(SecurityErrorCode.FORBIDDEN);
    }

    /** {@code JwtAuthenticationProvider} 가 디코더 예외를 감싸는 모양 그대로 만든다. */
    private static InvalidBearerTokenException bearerFailure(BadJwtException cause) {
        return new InvalidBearerTokenException(cause.getMessage(), cause);
    }
}
