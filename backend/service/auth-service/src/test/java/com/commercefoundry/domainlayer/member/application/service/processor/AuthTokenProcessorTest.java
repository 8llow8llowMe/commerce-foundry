package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AdminAccessTokenInfo;
import com.commercefoundry.domainlayer.member.application.info.AuthTokenInfo;
import com.commercefoundry.domainlayer.member.application.port.out.AccessTokenRevocationPort;
import com.commercefoundry.domainlayer.member.application.port.out.RefreshTokenStorePort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotation;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationPolicy;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationResult;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenSession;
import com.commercefoundry.security.issuer.OpaqueTokenGenerator;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 발급기 · 지문 생성기는 실제 것(테스트 키 · 고정 시계)을 쓰고 저장소 포트만 mock 이다.
 */
class AuthTokenProcessorTest {

    private static final Duration REFRESH_TTL = Duration.ofDays(14);
    private static final Duration GRACE = Duration.ofSeconds(30);
    private static final RefreshTokenRotationPolicy POLICY = new RefreshTokenRotationPolicy(GRACE, 3, REFRESH_TTL, Duration.ofDays(30));
    private static final Member CUSTOMER = Member.restore(TestTokens.MEMBER_ID, "a@example.com", "$2a$10$hash", "홍길동", null, MemberRole.CUSTOMER, 0L);

    private final RefreshTokenStorePort store = mock(RefreshTokenStorePort.class);
    private final AccessTokenRevocationPort revocation = mock(AccessTokenRevocationPort.class);
    private final OpaqueTokenGenerator opaqueTokenGenerator = new OpaqueTokenGenerator();
    private final AuthTokenProcessor processor = new AuthTokenProcessor(
        store, revocation, TestTokens.issuer(TestTokens.NOW), opaqueTokenGenerator, POLICY, TestTokens.FIXED_CLOCK
    );

    @Test
    @DisplayName("로그인은 새 family(UUID)로 refresh 지문을 지금(로그인 시각)과 함께 14일 저장하고, 원문 43자 · access 30분(1800초) · refresh 14일(초)을 준다")
    void issueForLogin() {
        AuthTokenInfo info = processor.issueForLogin(CUSTOMER);

        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> familyId = ArgumentCaptor.forClass(String.class);
        verify(store).saveNewFamily(fingerprint.capture(), eq(TestTokens.MEMBER_ID), familyId.capture(), eq(TestTokens.NOW), eq(REFRESH_TTL));
        assertThat(info.refreshToken()).hasSize(43);
        assertThat(fingerprint.getValue()).as("원문이 아니라 지문을 저장한다").isEqualTo(opaqueTokenGenerator.fingerprint(info.refreshToken())).isNotEqualTo(info.refreshToken());
        assertThat(UUID.fromString(familyId.getValue())).isNotNull();
        assertThat(info.accessTokenExpiresIn()).isEqualTo(1800);
        assertThat(info.refreshTokenExpiresIn()).isEqualTo(REFRESH_TTL.toSeconds());
        assertThat(info.toString()).doesNotContain(info.accessToken(), info.refreshToken());
    }

    @Test
    @DisplayName("관리자 로그인은 access 만 발급한다 — role=ADMIN · 30분(1800초)이고 refresh 저장소 · 차단 목록을 부르지 않는다 (#6)")
    void issueAdminAccessToken() throws Exception {
        Member admin = Member.restore(TestTokens.MEMBER_ID, "admin@example.com", "$2a$10$hash", "관리자", null, MemberRole.ADMIN, 0L);

        AdminAccessTokenInfo info = processor.issueAdminAccessToken(admin);

        assertThat(info.accessTokenExpiresIn()).isEqualTo(1800);
        assertThat(SignedJWT.parse(info.accessToken()).getJWTClaimsSet().getStringClaim(MemberRole.CLAIM_NAME)).isEqualTo("ADMIN");
        assertThat(SignedJWT.parse(info.accessToken()).getJWTClaimsSet().getSubject()).isEqualTo(String.valueOf(TestTokens.MEMBER_ID));
        assertThat(info.toString()).doesNotContain(info.accessToken());
        verifyNoInteractions(store, revocation);
    }

    @Test
    @DisplayName("OTP 2단계 토큰은 챌린지 발급 초를 iat 로 쓰고 실제 서명 초를 만료 기준으로 쓴다 — 이후 컷오프가 옛 챌린지 토큰을 끊는다")
    void issueAdminAccessTokenForChallenge() throws Exception {
        Member admin = Member.restore(TestTokens.MEMBER_ID, "admin@example.com", "$2a$10$hash", "관리자", null, MemberRole.ADMIN, 0L);
        Instant challengeIssuedAt = TestTokens.NOW.minusSeconds(2).plusMillis(800);

        AdminAccessTokenInfo info = processor.issueAdminAccessToken(admin, challengeIssuedAt);
        var claims = SignedJWT.parse(info.accessToken()).getJWTClaimsSet();

        assertThat(claims.getIssueTime().toInstant()).isEqualTo(TestTokens.NOW.minusSeconds(2));
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(TestTokens.NOW.plusSeconds(1800));
        assertThat(claims.getIssueTime().toInstant()).isBefore(TestTokens.NOW.plusSeconds(1));
        assertThat(info.accessTokenExpiresIn()).isEqualTo(1800);
        verifyNoInteractions(store, revocation);
    }

    @Test
    @DisplayName("고객 계정으로 관리자 access 를 만들지 않는다 — 호출 코드의 오류다")
    void adminAccessTokenRequiresAdmin() {
        assertThatThrownBy(() -> processor.issueAdminAccessToken(CUSTOMER)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store, revocation);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCg", "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg=", "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg"})
    @DisplayName("없음 · 빈 값 · 발급 모양(base64url 43자)이 아니면 저장소를 부르지 않고 AUTH_010 이다")
    void malformed(String refreshToken) {
        assertError(() -> processor.findSession(refreshToken), MemberErrorCode.REFRESH_TOKEN_MALFORMED);
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("저장소에 없으면 AUTH_011, 있으면 지문으로 찾은 세션이다")
    void findSession() {
        String refreshToken = opaqueTokenGenerator.generate();
        String fingerprint = opaqueTokenGenerator.fingerprint(refreshToken);
        RefreshTokenSession session = new RefreshTokenSession(fingerprint, TestTokens.MEMBER_ID, UUID.randomUUID().toString());

        given(store.findSession(fingerprint)).willReturn(Optional.empty());
        assertError(() -> processor.findSession(refreshToken), MemberErrorCode.REFRESH_TOKEN_INVALID);

        given(store.findSession(fingerprint)).willReturn(Optional.of(session));
        assertThat(processor.findSession(refreshToken)).isEqualTo(session);
    }

    @ParameterizedTest
    @EnumSource(value = RefreshTokenRotation.class, names = {"ROTATED", "REISSUED_IN_GRACE"})
    @DisplayName("회전 · 유예 안 형제 발급이면 새 쌍을 준다 — 지금 시각 · 정책(유예 30초 · 형제 3 · TTL 14일 · 상한 30일) · 새 지문으로 회전한다")
    void reissued(RefreshTokenRotation rotation) {
        RefreshTokenSession session = session();
        given(store.rotate(eq(session), anyString(), eq(TestTokens.NOW), eq(POLICY))).willReturn(new RefreshTokenRotationResult(rotation, REFRESH_TTL));

        AuthTokenInfo info = processor.reissue(session, CUSTOMER);

        ArgumentCaptor<String> newFingerprint = ArgumentCaptor.forClass(String.class);
        verify(store).rotate(eq(session), newFingerprint.capture(), eq(TestTokens.NOW), eq(POLICY));
        assertThat(newFingerprint.getValue()).isEqualTo(opaqueTokenGenerator.fingerprint(info.refreshToken()));
        assertThat(info.accessTokenExpiresIn()).isEqualTo(1800);
        assertThat(info.refreshTokenExpiresIn()).isEqualTo(REFRESH_TTL.toSeconds());
    }

    @ParameterizedTest
    @EnumSource(value = RefreshTokenRotation.class, names = {"ROTATED", "REISSUED_IN_GRACE"})
    @DisplayName("재발급 응답의 refresh 수명은 저장소가 실제로 건 TTL(상한까지 남은 시간)의 초다 — 밀리초는 버린다")
    void reissuedTtlIsWhatStoreApplied(RefreshTokenRotation rotation) {
        given(store.rotate(any(), anyString(), any(), any())).willReturn(new RefreshTokenRotationResult(rotation, Duration.ofHours(5).plusMillis(999)));

        assertThat(processor.reissue(session(), CUSTOMER).refreshTokenExpiresIn()).isEqualTo(Duration.ofHours(5).toSeconds());
    }

    @Test
    @DisplayName("읽은 뒤 무효가 됐으면 AUTH_011 이다")
    void invalidAfterRead() {
        given(store.rotate(any(), anyString(), any(), any())).willReturn(RefreshTokenRotationResult.rejected(RefreshTokenRotation.INVALID));

        assertError(() -> processor.reissue(session(), CUSTOMER), MemberErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    @DisplayName("유예 밖 재사용 · 형제 상한 초과면 AUTH_012 다 (family 폐기는 저장소 스크립트가 이미 했다)")
    void reuseDetected() {
        given(store.rotate(any(), anyString(), any(), any())).willReturn(RefreshTokenRotationResult.rejected(RefreshTokenRotation.REUSE_DETECTED));

        assertError(() -> processor.reissue(session(), CUSTOMER), MemberErrorCode.REFRESH_TOKEN_REUSED);
        verify(store, never()).revokeFamily(anyString());
    }

    @Test
    @DisplayName("family 절대 수명 초과면 재사용(AUTH_012)이 아니라 401 · AUTH_011 이다 — family 폐기는 저장소 스크립트가 이미 했다")
    void familyExpired() {
        RefreshTokenSession session = session();
        given(store.rotate(eq(session), anyString(), eq(TestTokens.NOW), eq(POLICY))).willReturn(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_EXPIRED));

        assertError(() -> processor.reissue(session, CUSTOMER), MemberErrorCode.REFRESH_TOKEN_INVALID);
        verify(store, times(1)).rotate(eq(session), anyString(), eq(TestTokens.NOW), eq(POLICY));
        verify(store, never()).revokeFamily(anyString());
        verifyNoInteractions(revocation);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("family 값 손상이면 401 · AUTH_011 이고 warn 한 줄을 남긴다 — memberId · familyId 만, 토큰 지문은 없다. family 폐기는 저장소 스크립트가 이미 했다")
    void familyCorrupted(CapturedOutput output) {
        RefreshTokenSession session = session();
        given(store.rotate(eq(session), anyString(), eq(TestTokens.NOW), eq(POLICY))).willReturn(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_CORRUPTED));

        assertError(() -> processor.reissue(session, CUSTOMER), MemberErrorCode.REFRESH_TOKEN_INVALID);

        assertThat(output.getOut().lines().filter(line -> line.contains("refresh token family value is corrupted")))
            .singleElement().asString()
            .contains("WARN", "memberId=" + session.memberId(), "familyId=" + session.familyId())
            .doesNotContain("fingerprint", session.fingerprint().substring(0, 8));
        verify(store, never()).revokeFamily(anyString());
        verifyNoInteractions(revocation);
    }

    @Test
    @DisplayName("고객 주인이 없으면(null — 없음 · 관리자) 회전하지 않고 family 를 폐기한 뒤 AUTH_011 이다")
    void ownerMissingOrAdmin() {
        RefreshTokenSession session = session();

        assertError(() -> processor.reissue(session, null), MemberErrorCode.REFRESH_TOKEN_INVALID);

        verify(store, times(1)).revokeFamily(session.familyId());
        verify(store, never()).rotate(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("로그아웃은 내 refresh family 를 먼저 지우고 그다음 jti 를 차단한다")
    void logoutRevokesFamilyThenAccessToken() {
        String refreshToken = opaqueTokenGenerator.generate();
        RefreshTokenSession session = new RefreshTokenSession(opaqueTokenGenerator.fingerprint(refreshToken), TestTokens.MEMBER_ID, UUID.randomUUID().toString());
        given(store.findSession(session.fingerprint())).willReturn(Optional.of(session));

        processor.logout(TestTokens.MEMBER_ID, "jti-1", refreshToken);

        InOrder order = inOrder(store, revocation);
        order.verify(store).revokeFamily(session.familyId());
        order.verify(revocation).revoke("jti-1");
    }

    @Test
    @DisplayName("남의 refresh 토큰이면 family 를 건드리지 않고 jti 만 차단한다")
    void logoutIgnoresOthersRefreshToken() {
        String refreshToken = opaqueTokenGenerator.generate();
        given(store.findSession(anyString())).willReturn(Optional.of(new RefreshTokenSession(opaqueTokenGenerator.fingerprint(refreshToken), 99L, "f")));

        processor.logout(TestTokens.MEMBER_ID, "jti-1", refreshToken);

        verify(store, never()).revokeFamily(anyString());
        verify(revocation).revoke("jti-1");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "broken")
    @DisplayName("refresh 토큰이 없거나 모양이 틀리면 조회 없이 jti 만 차단한다")
    void logoutWithoutUsableRefreshToken(String refreshToken) {
        processor.logout(TestTokens.MEMBER_ID, "jti-1", refreshToken);

        verify(store, never()).findSession(anyString());
        verify(revocation).revoke("jti-1");
    }

    @Test
    @DisplayName("family 폐기가 저장소 장애면 차단 목록에 쓰지 않고 그대로 AUTH_013 이다")
    void logoutFailsFast() {
        String refreshToken = opaqueTokenGenerator.generate();
        given(store.findSession(anyString())).willThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE));

        assertError(() -> processor.logout(TestTokens.MEMBER_ID, "jti-1", refreshToken), MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        verifyNoInteractions(revocation);
        verify(store, never()).saveNewFamily(anyString(), anyLong(), anyString(), any(), any());
    }

    private RefreshTokenSession session() {
        return new RefreshTokenSession(opaqueTokenGenerator.fingerprint(opaqueTokenGenerator.generate()), TestTokens.MEMBER_ID, UUID.randomUUID().toString());
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, MemberErrorCode errorCode) {
        assertThatThrownBy(call)
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(errorCode);
    }
}
