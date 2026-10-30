package com.commercefoundry.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AdminLoginResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.presenter.AdminAuthWebPresenter;
import com.commercefoundry.domainlayer.member.application.command.AdminLoginCommand;
import com.commercefoundry.domainlayer.member.application.command.AdminOtpLoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.LoginAttemptLimitedException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AdminAccessTokenInfo;
import com.commercefoundry.domainlayer.member.application.info.AdminLoginChallengeInfo;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpChallenge;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpProof;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditPort;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccessCutoffProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminLoginChallengeProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminOtpProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AuthTokenProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.LoginGuardProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberLoginProcessor;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class AdminAuthWebFacadeTest {

    private static final Member ADMIN = Member.restore(9L, "admin@example.com", "$2a$10$admin", "관리자", null, MemberRole.ADMIN, 0L);
    private static final Member CUSTOMER = Member.restore(7L, "a@example.com", "$2a$10$hash", "홍길동", null, MemberRole.CUSTOMER, 0L);
    private static final AdminLoginCommand COMMAND = new AdminLoginCommand("Admin@Example.com", "admin-password-1");
    private static final String EMAIL = "admin@example.com";
    private static final Instant NOW = Instant.parse("2026-10-29T03:00:00Z");
    private static final String CHALLENGE = "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg";
    private static final AdminOtpLoginCommand OTP_COMMAND = new AdminOtpLoginCommand(CHALLENGE, "ABCDE-FGH23");
    private static final AdminOtpProof PROOF = new AdminOtpProof.RecoveryCodeMatch(55L);
    private static final AdminOtpChallenge STORED_CHALLENGE = new AdminOtpChallenge(9L, NOW.minusSeconds(30));

    @Mock
    private MemberLoginProcessor memberLoginProcessor;

    @Mock
    private LoginGuardProcessor loginGuardProcessor;

    @Mock
    private AuthTokenProcessor authTokenProcessor;

    @Mock
    private AdminAuditPort adminAuditPort;

    @Mock
    private AdminAccountProcessor adminAccountProcessor;

    @Mock
    private AdminOtpProcessor adminOtpProcessor;

    @Mock
    private AdminLoginChallengeProcessor adminLoginChallengeProcessor;

    @Mock
    private AdminAccessCutoffProcessor adminAccessCutoffProcessor;

    private AdminAuthWebFacade facade;

    @BeforeEach
    void setUp() {
        facade = new AdminAuthWebFacade(memberLoginProcessor, loginGuardProcessor, authTokenProcessor, new AdminAuditProcessor(adminAuditPort),
            adminAccountProcessor, adminOtpProcessor, adminLoginChallengeProcessor, adminAccessCutoffProcessor,
            new AdminAuthWebPresenter(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("정규화 → 관리자 시도 제한(ADMIN_LOGIN) → 조회 → 관리자 인증 → access 발급 순서이고, 성공을 감사 로그에 남긴다 — 성공해도 창을 지우지 않는다")
    void login() {
        given(memberLoginProcessor.normalizeEmail("Admin@Example.com")).willReturn(Optional.of(EMAIL));
        given(memberLoginProcessor.findByEmail(EMAIL)).willReturn(Optional.of(ADMIN));
        given(memberLoginProcessor.authenticateAdmin(Optional.of(ADMIN), "admin-password-1")).willReturn(ADMIN);
        given(authTokenProcessor.issueAdminAccessToken(ADMIN)).willReturn(new AdminAccessTokenInfo("access", 1800));

        AdminLoginResponse response = facade.login(COMMAND);

        assertThat(response).isEqualTo(AdminLoginResponse.builder().accessToken("access").accessTokenExpiresIn(1800L).build());
        InOrder order = inOrder(memberLoginProcessor, loginGuardProcessor, authTokenProcessor, adminAuditPort);
        order.verify(loginGuardProcessor).admit(AttemptKind.ADMIN_LOGIN, EMAIL);
        order.verify(memberLoginProcessor).findByEmail(EMAIL);
        order.verify(memberLoginProcessor).authenticateAdmin(Optional.of(ADMIN), "admin-password-1");
        order.verify(authTokenProcessor).issueAdminAccessToken(ADMIN);
        order.verify(adminAuditPort).adminLoginSucceeded(9L);
        verify(adminAuditPort, never()).adminLoginFailed(any());
        verify(loginGuardProcessor, never()).admit(AttemptKind.LOGIN, EMAIL);
        // 성공 DEL 이 관리자 여부 · 로그인 시점의 오라클이 되지 않게 관리자 창은 성공해도 지우지 않는다 (사용자 결정 2026-10-22)
        verify(loginGuardProcessor, never()).clear(any(), any());
    }

    @Test
    @DisplayName("관리자 계정을 노린 실패는 그 memberId 를 대상으로 감사하고 AUTH_030 을 그대로 던진다 — 창을 지우지 않고 토큰을 만들지 않는다")
    void failedOnAdminAccount() {
        given(memberLoginProcessor.normalizeEmail(any())).willReturn(Optional.of(EMAIL));
        given(memberLoginProcessor.findByEmail(EMAIL)).willReturn(Optional.of(ADMIN));
        given(memberLoginProcessor.authenticateAdmin(Optional.of(ADMIN), "admin-password-1"))
            .willThrow(new MemberException(MemberErrorCode.ADMIN_LOGIN_FAILED));

        assertThatThrownBy(() -> facade.login(COMMAND))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.ADMIN_LOGIN_FAILED);
        verify(adminAuditPort).adminLoginFailed(OptionalLong.of(9L));
        verify(adminAuditPort, never()).adminLoginSucceeded(anyLong());
        verify(loginGuardProcessor, never()).clear(any(), any());
        verify(authTokenProcessor, never()).issueAdminAccessToken(any());
    }

    @Test
    @DisplayName("고객 계정 · 없는 이메일의 실패는 대상 없이 감사한다 — 고객 memberId 를 관리자 감사 로그에 남기지 않는다")
    void failedWithoutAdminTarget() {
        given(memberLoginProcessor.normalizeEmail(any())).willReturn(Optional.of(EMAIL));
        given(memberLoginProcessor.findByEmail(EMAIL)).willReturn(Optional.of(CUSTOMER), Optional.empty());
        given(memberLoginProcessor.authenticateAdmin(any(), any())).willThrow(new MemberException(MemberErrorCode.ADMIN_LOGIN_FAILED));

        assertThatThrownBy(() -> facade.login(COMMAND)).isInstanceOf(MemberException.class);
        assertThatThrownBy(() -> facade.login(COMMAND)).isInstanceOf(MemberException.class);

        verify(adminAuditPort, times(2)).adminLoginFailed(OptionalLong.empty());
    }

    @Test
    @DisplayName("시도 초과면 조회 · BCrypt · 창 지우기 · 발급 없이 429 AUTH_040 을 던지고, 대상 없는 실패(target=-)로 감사한다")
    void limitedSkipsLookupAndAuditsWithoutTarget() {
        given(memberLoginProcessor.normalizeEmail(any())).willReturn(Optional.of(EMAIL));
        LoginAttemptLimitedException limited = new LoginAttemptLimitedException(MemberErrorCode.LOGIN_ATTEMPT_LIMITED, Duration.ofMinutes(3));
        willThrow(limited).given(loginGuardProcessor).admit(AttemptKind.ADMIN_LOGIN, EMAIL);

        assertThatThrownBy(() -> facade.login(COMMAND)).isSameAs(limited);

        verify(memberLoginProcessor, never()).findByEmail(anyString());
        verify(memberLoginProcessor, never()).authenticateAdmin(any(), any());
        verify(loginGuardProcessor, never()).clear(any(), any());
        verifyNoInteractions(authTokenProcessor);
        verify(adminAuditPort).adminLoginFailed(OptionalLong.empty());
        verify(adminAuditPort, never()).adminLoginSucceeded(anyLong());
    }

    @Test
    @DisplayName("계수기 장애(503 AUTH_013)는 잡지 않는다(fail-closed) — 조회 · BCrypt · 감사 없이 그대로 올라간다")
    void counterUnavailableFailsClosed() {
        given(memberLoginProcessor.normalizeEmail(any())).willReturn(Optional.of(EMAIL));
        willThrow(new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE)).given(loginGuardProcessor).admit(AttemptKind.ADMIN_LOGIN, EMAIL);

        assertThatThrownBy(() -> facade.login(COMMAND))
            .isInstanceOf(MemberException.class)
            .extracting(failure -> ((MemberException) failure).getErrorCode()).isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);

        verify(memberLoginProcessor, never()).findByEmail(anyString());
        verify(memberLoginProcessor, never()).authenticateAdmin(any(), any());
        verifyNoInteractions(authTokenProcessor, adminAuditPort);
    }

    @Test
    @DisplayName("정규화할 수 없는 이메일은 세지도 조회하지도 않고 없는 회원과 같은 더미 비교로 AUTH_030 — 대상 없이 감사한다")
    void unnormalizableEmailIsAbsentMember() {
        given(memberLoginProcessor.normalizeEmail(any())).willReturn(Optional.empty());
        given(memberLoginProcessor.authenticateAdmin(Optional.empty(), "admin-password-1")).willThrow(new MemberException(MemberErrorCode.ADMIN_LOGIN_FAILED));

        assertThatThrownBy(() -> facade.login(COMMAND)).isInstanceOf(MemberException.class);

        verify(memberLoginProcessor, never()).findByEmail(anyString());
        verifyNoInteractions(loginGuardProcessor, authTokenProcessor);
        verify(adminAuditPort).adminLoginFailed(OptionalLong.empty());
    }

    // ── OTP 등록 관리자 (#238 PR-B)

    @Test
    @DisplayName("OTP 를 등록한 관리자는 비밀번호가 맞아도 토큰 없이 챌린지만 받는다 — 1단계 통과 감사는 남긴다")
    void enrolledAdminGetsChallengeOnly() {
        given(memberLoginProcessor.normalizeEmail(any())).willReturn(Optional.of(EMAIL));
        given(memberLoginProcessor.findByEmail(EMAIL)).willReturn(Optional.of(ADMIN));
        given(memberLoginProcessor.authenticateAdmin(Optional.of(ADMIN), "admin-password-1")).willReturn(ADMIN);
        given(adminOtpProcessor.isEnrolled(9L)).willReturn(true);
        given(adminLoginChallengeProcessor.issue(9L, NOW)).willReturn(new AdminLoginChallengeInfo(CHALLENGE, 300));

        AdminLoginResponse response = facade.login(COMMAND);

        assertThat(response).isEqualTo(AdminLoginResponse.builder().otpRequired(true).challengeToken(CHALLENGE).challengeExpiresIn(300L).build());
        verifyNoInteractions(authTokenProcessor);
        verify(adminAuditPort).adminLoginPasswordVerified(9L);
        verify(adminAuditPort, never()).adminLoginSucceeded(anyLong());
        verify(loginGuardProcessor, never()).clear(any(), any());
    }

    @Test
    @DisplayName("비밀번호 확인 중 시간이 넘어가도 챌린지는 회원 조회 전 시각을 쓴다 — 그 사이 변경된 비밀번호의 컷오프가 무효화한다")
    void challengeIssuedAtPrecedesPasswordLookup() {
        AtomicReference<Instant> time = new AtomicReference<>(NOW);
        Clock movingClock = mock(Clock.class);
        given(movingClock.instant()).willAnswer(ignored -> time.get());
        facade = new AdminAuthWebFacade(memberLoginProcessor, loginGuardProcessor, authTokenProcessor, new AdminAuditProcessor(adminAuditPort),
            adminAccountProcessor, adminOtpProcessor, adminLoginChallengeProcessor, adminAccessCutoffProcessor,
            new AdminAuthWebPresenter(), movingClock);
        given(memberLoginProcessor.normalizeEmail(any())).willReturn(Optional.of(EMAIL));
        given(memberLoginProcessor.findByEmail(EMAIL)).willReturn(Optional.of(ADMIN));
        given(memberLoginProcessor.authenticateAdmin(Optional.of(ADMIN), "admin-password-1")).willAnswer(ignored -> {
            time.set(NOW.plusSeconds(2));
            return ADMIN;
        });
        given(adminOtpProcessor.isEnrolled(9L)).willReturn(true);
        given(adminLoginChallengeProcessor.issue(9L, NOW)).willReturn(new AdminLoginChallengeInfo(CHALLENGE, 300));

        assertThat(facade.login(COMMAND).challengeToken()).isEqualTo(CHALLENGE);

        verify(adminLoginChallengeProcessor).issue(9L, NOW);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 OTP 등록 여부를 보지 않는다 — 등록 여부가 비밀번호 확인 전에 드러나지 않게")
    void wrongPasswordDoesNotLookAtOtp() {
        given(memberLoginProcessor.normalizeEmail(any())).willReturn(Optional.of(EMAIL));
        given(memberLoginProcessor.findByEmail(EMAIL)).willReturn(Optional.of(ADMIN));
        given(memberLoginProcessor.authenticateAdmin(any(), any())).willThrow(new MemberException(MemberErrorCode.ADMIN_LOGIN_FAILED));

        assertThatThrownBy(() -> facade.login(COMMAND)).isInstanceOf(MemberException.class);

        verifyNoInteractions(adminOtpProcessor, adminLoginChallengeProcessor);
    }

    @Test
    @DisplayName("2단계는 챌린지 조회 → 시도 제한 → 활성 관리자 → 컷오프 → 코드 확인 → 챌린지 소비 → 코드 소비 → 발급 순서다")
    void otpLogin() {
        given(adminLoginChallengeProcessor.find(CHALLENGE)).willReturn(STORED_CHALLENGE);
        given(adminAccountProcessor.findActiveAdmin(9L)).willReturn(ADMIN);
        given(adminOtpProcessor.resolveProof(9L, "ABCDE-FGH23", NOW)).willReturn(PROOF);
        given(authTokenProcessor.issueAdminAccessToken(ADMIN, STORED_CHALLENGE.issuedAt())).willReturn(new AdminAccessTokenInfo("access", 1800));

        AdminLoginResponse response = facade.loginWithOtp(OTP_COMMAND);

        assertThat(response).isEqualTo(AdminLoginResponse.builder().accessToken("access").accessTokenExpiresIn(1800L).otpRequired(false).build());
        InOrder order = inOrder(adminLoginChallengeProcessor, loginGuardProcessor, adminAccountProcessor, adminAccessCutoffProcessor,
            adminOtpProcessor, authTokenProcessor, adminAuditPort);
        order.verify(adminLoginChallengeProcessor).find(CHALLENGE);
        order.verify(loginGuardProcessor).admitAdminOtpLogin(9L);
        order.verify(adminAccountProcessor).findActiveAdmin(9L);
        order.verify(adminAccessCutoffProcessor).issuedBeforeCutoff(9L, STORED_CHALLENGE.issuedAt());
        order.verify(adminOtpProcessor).resolveProof(9L, "ABCDE-FGH23", NOW);
        order.verify(adminLoginChallengeProcessor).consume(CHALLENGE, STORED_CHALLENGE);
        order.verify(adminOtpProcessor).consumeForLogin(9L, PROOF, NOW);
        order.verify(authTokenProcessor).issueAdminAccessToken(ADMIN, STORED_CHALLENGE.issuedAt());
        order.verify(adminAuditPort).adminLoginSucceeded(9L);
        verify(adminAuditPort, never()).adminLoginFailed(any());
        verify(loginGuardProcessor, never()).admitAdminOtp(anyLong());
        verify(loginGuardProcessor, never()).clearAdminOtp(anyLong());
    }

    @Test
    @DisplayName("챌린지가 없거나 만료면 400 AUTH_034 — 대상을 모르므로 세지도 감사하지도 않는다")
    void invalidChallengeIsNotCountedOrAudited() {
        willThrow(new MemberException(MemberErrorCode.OTP_CHALLENGE_INVALID)).given(adminLoginChallengeProcessor).find(CHALLENGE);

        assertThatThrownBy(() -> facade.loginWithOtp(OTP_COMMAND)).extracting(failure -> ((MemberException) failure).getErrorCode())
            .isEqualTo(MemberErrorCode.OTP_CHALLENGE_INVALID);

        verifyNoInteractions(loginGuardProcessor, adminAccountProcessor, adminOtpProcessor, authTokenProcessor, adminAuditPort);
    }

    @Test
    @DisplayName("2단계 시도 초과(429 AUTH_043)는 코드를 보지 않고 감사하지 않는다(판정 전 실패)")
    void limitedSecondStep() {
        given(adminLoginChallengeProcessor.find(CHALLENGE)).willReturn(STORED_CHALLENGE);
        LoginAttemptLimitedException limited = new LoginAttemptLimitedException(MemberErrorCode.OTP_ATTEMPT_LIMITED, Duration.ofMinutes(2));
        willThrow(limited).given(loginGuardProcessor).admitAdminOtpLogin(9L);

        assertThatThrownBy(() -> facade.loginWithOtp(OTP_COMMAND)).isSameAs(limited);

        verifyNoInteractions(adminAccountProcessor, adminOtpProcessor, authTokenProcessor, adminAuditPort);
        verify(adminLoginChallengeProcessor, never()).consume(anyString(), any(AdminOtpChallenge.class));
    }

    @Test
    @DisplayName("틀린 코드는 400 AUTH_035 그대로이고 대상 관리자로 실패 감사한다 — 챌린지는 소비하지 않아 TTL 안에서 다시 시도할 수 있다")
    void wrongCode() {
        given(adminLoginChallengeProcessor.find(CHALLENGE)).willReturn(STORED_CHALLENGE);
        given(adminAccountProcessor.findActiveAdmin(9L)).willReturn(ADMIN);
        willThrow(new MemberException(MemberErrorCode.OTP_CODE_INVALID)).given(adminOtpProcessor).resolveProof(9L, "ABCDE-FGH23", NOW);

        assertThatThrownBy(() -> facade.loginWithOtp(OTP_COMMAND)).extracting(failure -> ((MemberException) failure).getErrorCode())
            .isEqualTo(MemberErrorCode.OTP_CODE_INVALID);

        verify(adminAuditPort).adminLoginFailed(OptionalLong.of(9L));
        verify(adminAuditPort, never()).adminLoginSucceeded(anyLong());
        verify(adminOtpProcessor, never()).consumeForLogin(anyLong(), any(), any());
        verify(adminLoginChallengeProcessor, never()).consume(anyString(), any(AdminOtpChallenge.class));
        verifyNoInteractions(authTokenProcessor);
    }

    @Test
    @DisplayName("비밀번호 변경 컷오프 이전 챌린지는 AUTH_034 로 거절하고 코드와 토큰을 소비하지 않는다")
    void oldChallengeAfterPasswordChange() {
        given(adminLoginChallengeProcessor.find(CHALLENGE)).willReturn(STORED_CHALLENGE);
        given(adminAccountProcessor.findActiveAdmin(9L)).willReturn(ADMIN);
        given(adminAccessCutoffProcessor.issuedBeforeCutoff(9L, STORED_CHALLENGE.issuedAt())).willReturn(true);

        assertThatThrownBy(() -> facade.loginWithOtp(OTP_COMMAND)).extracting(failure -> ((MemberException) failure).getErrorCode())
            .isEqualTo(MemberErrorCode.OTP_CHALLENGE_INVALID);

        verifyNoInteractions(adminOtpProcessor, authTokenProcessor);
        verify(adminLoginChallengeProcessor, never()).consume(anyString(), any(AdminOtpChallenge.class));
        verify(adminAuditPort).adminLoginFailed(OptionalLong.of(9L));
    }

    @Test
    @DisplayName("GETDEL 동시 패자는 코드를 소비하지 않고 AUTH_034 로 실패 감사한다")
    void lostChallengeDoesNotConsumeCode() {
        given(adminLoginChallengeProcessor.find(CHALLENGE)).willReturn(STORED_CHALLENGE);
        given(adminAccountProcessor.findActiveAdmin(9L)).willReturn(ADMIN);
        given(adminOtpProcessor.resolveProof(9L, "ABCDE-FGH23", NOW)).willReturn(PROOF);
        willThrow(new MemberException(MemberErrorCode.OTP_CHALLENGE_INVALID)).given(adminLoginChallengeProcessor)
            .consume(CHALLENGE, STORED_CHALLENGE);

        assertThatThrownBy(() -> facade.loginWithOtp(OTP_COMMAND)).extracting(failure -> ((MemberException) failure).getErrorCode())
            .isEqualTo(MemberErrorCode.OTP_CHALLENGE_INVALID);

        verify(adminOtpProcessor, never()).consumeForLogin(anyLong(), any(), any());
        verifyNoInteractions(authTokenProcessor);
        verify(adminAuditPort).adminLoginFailed(OptionalLong.of(9L));
    }

    @Test
    @DisplayName("대상을 안 뒤 예상 밖 예외도 실패 감사하고 그대로 전파한다")
    void unexpectedFailureIsAudited() {
        given(adminLoginChallengeProcessor.find(CHALLENGE)).willReturn(STORED_CHALLENGE);
        RuntimeException failure = new IllegalStateException("test failure");
        given(adminAccountProcessor.findActiveAdmin(9L)).willThrow(failure);

        assertThatThrownBy(() -> facade.loginWithOtp(OTP_COMMAND)).isSameAs(failure);

        verify(adminAuditPort).adminLoginFailed(OptionalLong.of(9L));
        verifyNoInteractions(adminOtpProcessor, authTokenProcessor);
    }

    @Test
    @DisplayName("그 사이 비활성화(AUTH_033) · OTP 해제(AUTH_036 — 확인 · 소비 어느 쪽이든)는 챌린지 무효 AUTH_034 로 답하고 실패 감사한다")
    void deactivatedOrDisabledMeansInvalidChallenge() {
        given(adminLoginChallengeProcessor.find(CHALLENGE)).willReturn(STORED_CHALLENGE);
        given(adminAccountProcessor.findActiveAdmin(9L)).willThrow(new MemberException(MemberErrorCode.ADMIN_ACCOUNT_NOT_FOUND)).willReturn(ADMIN);
        given(adminOtpProcessor.resolveProof(9L, "ABCDE-FGH23", NOW)).willThrow(new MemberException(MemberErrorCode.OTP_NOT_ENROLLED)).willReturn(PROOF);
        willThrow(new MemberException(MemberErrorCode.OTP_NOT_ENROLLED)).given(adminOtpProcessor).consumeForLogin(9L, PROOF, NOW);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> facade.loginWithOtp(OTP_COMMAND)).extracting(failure -> ((MemberException) failure).getErrorCode())
                .isEqualTo(MemberErrorCode.OTP_CHALLENGE_INVALID);
        }

        verify(adminAuditPort, times(3)).adminLoginFailed(OptionalLong.of(9L));
        verify(adminLoginChallengeProcessor, times(1)).consume(CHALLENGE, STORED_CHALLENGE);
        verifyNoInteractions(authTokenProcessor);
    }

    @Test
    @DisplayName("코드 소비의 잠금 실패는 409 AUTH_044, 챌린지 소비를 진 동시 요청은 400 AUTH_034 — 둘 다 토큰 없이 실패 감사한다")
    void busyOrLostChallenge() {
        given(adminLoginChallengeProcessor.find(CHALLENGE)).willReturn(STORED_CHALLENGE);
        given(adminAccountProcessor.findActiveAdmin(9L)).willReturn(ADMIN);
        given(adminOtpProcessor.resolveProof(9L, "ABCDE-FGH23", NOW)).willReturn(PROOF);
        willThrow(new PessimisticLockingFailureException("lock")).willDoNothing().given(adminOtpProcessor).consumeForLogin(9L, PROOF, NOW);
        willDoNothing().willThrow(new MemberException(MemberErrorCode.OTP_CHALLENGE_INVALID))
            .given(adminLoginChallengeProcessor).consume(CHALLENGE, STORED_CHALLENGE);

        assertThatThrownBy(() -> facade.loginWithOtp(OTP_COMMAND)).extracting(failure -> ((MemberException) failure).getErrorCode())
            .isEqualTo(MemberErrorCode.OTP_BUSY);
        assertThatThrownBy(() -> facade.loginWithOtp(OTP_COMMAND)).extracting(failure -> ((MemberException) failure).getErrorCode())
            .isEqualTo(MemberErrorCode.OTP_CHALLENGE_INVALID);

        verify(adminAuditPort, times(2)).adminLoginFailed(OptionalLong.of(9L));
        verifyNoInteractions(authTokenProcessor);
    }
}
