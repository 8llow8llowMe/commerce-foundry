package com.commercefoundry.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.admin.presenter.AdminOtpAdminPresenter;
import com.commercefoundry.domainlayer.member.application.exception.LoginAttemptLimitedException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AdminOtpEnrollmentInfo;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpProof;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpProof.RecoveryCodeMatch;
import com.commercefoundry.domainlayer.member.application.model.IssuedRecoveryCodes;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminOtpProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.LoginGuardProcessor;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * 관리자 본인 OTP Facade (#238 PR-A2) — 순서(시도 세기 → 비밀번호 → 코드 → 저장 → 창 비우기 → 감사), 실패 감사, 429 는 감사하지 않음.
 */
class AdminOtpFacadeTest {

    private static final long ADMIN_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-10-29T03:00:00Z");
    private static final Member ADMIN = Member.restore(ADMIN_ID, "owner@example.com", "hash", "점주", null, MemberRole.ADMIN, 0L);
    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final IssuedRecoveryCodes ISSUED = new IssuedRecoveryCodes(List.of("ABCDE-FGH23"), List.of("$2a$hash"));
    private static final AdminOtpProof PROOF = new RecoveryCodeMatch(55L);

    private final AdminOtpProcessor adminOtpProcessor = mock(AdminOtpProcessor.class);
    private final AdminAccountProcessor adminAccountProcessor = mock(AdminAccountProcessor.class);
    private final LoginGuardProcessor loginGuardProcessor = mock(LoginGuardProcessor.class);
    private final AdminAuditProcessor adminAuditProcessor = mock(AdminAuditProcessor.class);
    private final AdminOtpFacade facade = new AdminOtpFacade(adminOtpProcessor, adminAccountProcessor, loginGuardProcessor, adminAuditProcessor,
        new AdminOtpAdminPresenter(), Clock.fixed(NOW, ZoneOffset.UTC));

    private void passwordIs(boolean correct) {
        given(adminAccountProcessor.findActiveAdmin(ADMIN_ID)).willReturn(ADMIN);
        given(adminAccountProcessor.passwordMatches(ADMIN, "current-password1")).willReturn(correct);
    }

    private static void assertCode(Throwable thrown, MemberErrorCode code) {
        assertThat(thrown).isInstanceOf(MemberException.class);
        assertThat(((MemberException) thrown).getErrorCode()).isEqualTo(code);
    }

    @Test
    @DisplayName("등록 시작은 관리자 이메일을 라벨로 넘기고 감사하지 않는다")
    void startEnrollment() {
        given(adminAccountProcessor.findActiveAdmin(ADMIN_ID)).willReturn(ADMIN);
        given(adminOtpProcessor.startEnrollment(ADMIN_ID, "owner@example.com", NOW))
            .willReturn(new AdminOtpEnrollmentInfo("SECRET", "otpauth://totp/x", NOW.plus(Duration.ofMinutes(15))));

        assertThat(facade.startEnrollment(ADMIN_ID).secret()).isEqualTo("SECRET");
        verifyNoInteractions(adminAuditProcessor, loginGuardProcessor);
    }

    @Test
    @DisplayName("등록 확인 — 시도 세기 → 진행 중 등록 → 비밀번호 → 코드 → 저장 → 등록 지우기 → 창 비우기 → 성공 감사. 복구 코드 원문을 돌려준다")
    void confirmOrder() {
        passwordIs(true);
        given(adminOtpProcessor.findEnrollment(ADMIN_ID)).willReturn(SECRET);
        given(adminOtpProcessor.verifyEnrollmentCode(SECRET, "123456", NOW)).willReturn(99L);
        given(adminOtpProcessor.issueRecoveryCodes()).willReturn(ISSUED);

        assertThat(facade.confirmEnrollment(ADMIN_ID, "current-password1", "123456").recoveryCodes()).containsExactly("ABCDE-FGH23");

        InOrder order = inOrder(loginGuardProcessor, adminOtpProcessor, adminAccountProcessor, adminAuditProcessor);
        order.verify(loginGuardProcessor).admitAdminOtp(ADMIN_ID);
        order.verify(adminOtpProcessor).findEnrollment(ADMIN_ID);
        order.verify(adminAccountProcessor).passwordMatches(ADMIN, "current-password1");
        order.verify(adminOtpProcessor).verifyEnrollmentCode(SECRET, "123456", NOW);
        order.verify(adminOtpProcessor).confirm(ADMIN_ID, SECRET, 99L, NOW, ISSUED.hashes());
        order.verify(adminOtpProcessor).discardEnrollment(ADMIN_ID);
        order.verify(loginGuardProcessor).clearAdminOtp(ADMIN_ID);
        order.verify(adminAuditProcessor).adminOtpEnrolled(ADMIN_ID, true);
    }

    @Test
    @DisplayName("등록 확인에서 비밀번호가 틀리면 AUTH_032 · 실패 감사이고 코드를 보지 않으며 창을 비우지 않는다")
    void confirmWrongPassword() {
        passwordIs(false);
        given(adminOtpProcessor.findEnrollment(ADMIN_ID)).willReturn(SECRET);

        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> facade.confirmEnrollment(ADMIN_ID, "current-password1", "123456")),
            MemberErrorCode.ADMIN_PASSWORD_MISMATCH);

        verify(loginGuardProcessor).admitAdminOtp(ADMIN_ID);
        verify(adminOtpProcessor, never()).verifyEnrollmentCode(any(), anyString(), any());
        verify(loginGuardProcessor, never()).clearAdminOtp(anyLong());
        verify(adminAuditProcessor).adminOtpEnrolled(ADMIN_ID, false);
    }

    @Test
    @DisplayName("등록 확인 — 진행 중 등록이 없으면 비밀번호를 보기 전에 AUTH_038 · 실패 감사다")
    void confirmWithoutEnrollment() {
        willThrow(new MemberException(MemberErrorCode.OTP_ENROLLMENT_NOT_FOUND)).given(adminOtpProcessor).findEnrollment(ADMIN_ID);

        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> facade.confirmEnrollment(ADMIN_ID, "current-password1", "123456")),
            MemberErrorCode.OTP_ENROLLMENT_NOT_FOUND);
        verifyNoInteractions(adminAccountProcessor);
        verify(adminAuditProcessor).adminOtpEnrolled(ADMIN_ID, false);
    }

    @Test
    @DisplayName("시도 제한을 넘기면 429 그대로이고 비밀번호 · 코드를 보지 않으며 감사하지 않는다")
    void limitedBeforeAnything() {
        LoginAttemptLimitedException limited = new LoginAttemptLimitedException(MemberErrorCode.OTP_ATTEMPT_LIMITED, Duration.ofMinutes(3));
        willThrow(limited).given(loginGuardProcessor).admitAdminOtp(ADMIN_ID);

        assertThatThrownBy(() -> facade.confirmEnrollment(ADMIN_ID, "current-password1", "123456")).isSameAs(limited);
        assertThatThrownBy(() -> facade.disable(ADMIN_ID, "current-password1", "123456")).isSameAs(limited);
        assertThatThrownBy(() -> facade.regenerateRecoveryCodes(ADMIN_ID, "current-password1", "123456")).isSameAs(limited);

        verifyNoInteractions(adminOtpProcessor, adminAccountProcessor, adminAuditProcessor);
    }

    @Test
    @DisplayName("해제 — 비밀번호 → 두 번째 인자 확인 → 해제 트랜잭션 → 창 비우기 → 성공 감사")
    void disableOrder() {
        passwordIs(true);
        given(adminOtpProcessor.resolveProof(ADMIN_ID, "abcde-fgh23", NOW)).willReturn(PROOF);

        facade.disable(ADMIN_ID, "current-password1", "abcde-fgh23");

        InOrder order = inOrder(loginGuardProcessor, adminOtpProcessor, adminAccountProcessor, adminAuditProcessor);
        order.verify(loginGuardProcessor).admitAdminOtp(ADMIN_ID);
        order.verify(adminAccountProcessor).passwordMatches(ADMIN, "current-password1");
        order.verify(adminOtpProcessor).resolveProof(ADMIN_ID, "abcde-fgh23", NOW);
        order.verify(adminOtpProcessor).disable(ADMIN_ID, PROOF, NOW);
        order.verify(loginGuardProcessor).clearAdminOtp(ADMIN_ID);
        order.verify(adminAuditProcessor).adminOtpDisabled(ADMIN_ID, true);
    }

    @Test
    @DisplayName("해제에서 코드가 틀리거나 이미 썼으면(AUTH_035) 실패 감사 · 창 유지")
    void disableWrongCode() {
        passwordIs(true);
        given(adminOtpProcessor.resolveProof(ADMIN_ID, "123456", NOW)).willReturn(PROOF);
        willThrow(new MemberException(MemberErrorCode.OTP_CODE_INVALID)).given(adminOtpProcessor).disable(ADMIN_ID, PROOF, NOW);

        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> facade.disable(ADMIN_ID, "current-password1", "123456")),
            MemberErrorCode.OTP_CODE_INVALID);
        verify(adminAuditProcessor).adminOtpDisabled(ADMIN_ID, false);
        verify(loginGuardProcessor, never()).clearAdminOtp(anyLong());
    }

    @Test
    @DisplayName("재발급 — 두 번째 인자를 확인한 뒤에만 새 코드를 만들고, 잠금 대기 초과(AUTH_044)는 실패 감사 후 그대로 던진다")
    void regenerate() {
        passwordIs(true);
        given(adminOtpProcessor.resolveProof(ADMIN_ID, "123456", NOW)).willReturn(PROOF);
        given(adminOtpProcessor.issueRecoveryCodes()).willReturn(ISSUED);

        assertThat(facade.regenerateRecoveryCodes(ADMIN_ID, "current-password1", "123456").recoveryCodes()).containsExactly("ABCDE-FGH23");
        verify(adminOtpProcessor).replaceRecoveryCodes(ADMIN_ID, PROOF, ISSUED.hashes(), NOW);
        verify(adminAuditProcessor).adminOtpRecoveryCodesRegenerated(ADMIN_ID, true);

        willThrow(new MemberException(MemberErrorCode.OTP_BUSY)).given(adminOtpProcessor).replaceRecoveryCodes(ADMIN_ID, PROOF, ISSUED.hashes(), NOW);
        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> facade.regenerateRecoveryCodes(ADMIN_ID, "current-password1", "123456")),
            MemberErrorCode.OTP_BUSY);
        verify(adminAuditProcessor).adminOtpRecoveryCodesRegenerated(ADMIN_ID, false);
    }

    @Test
    @DisplayName("재발급에서 두 번째 인자가 틀리면 새 코드를 만들지 않는다(BCrypt 8번을 아낀다)")
    void regenerateWrongCodeSkipsHashing() {
        passwordIs(true);
        willThrow(new MemberException(MemberErrorCode.OTP_CODE_INVALID)).given(adminOtpProcessor).resolveProof(ADMIN_ID, "123456", NOW);

        assertCode(org.assertj.core.api.Assertions.catchThrowable(() -> facade.regenerateRecoveryCodes(ADMIN_ID, "current-password1", "123456")),
            MemberErrorCode.OTP_CODE_INVALID);
        verify(adminOtpProcessor, never()).issueRecoveryCodes();
        verify(adminAuditProcessor).adminOtpRecoveryCodesRegenerated(ADMIN_ID, false);
    }

    static Stream<RuntimeException> lockFailures() {
        return Stream.of(new CannotAcquireLockException("lock"), new QueryTimeoutException("cancelled"), new TransactionTimedOutException("deadline"));
    }

    @ParameterizedTest
    @MethodSource("lockFailures")
    @DisplayName("저장 트랜잭션의 잠금 실패 · 문장 취소 · 상한 초과는 확인 · 해제 · 재발급 모두 409 AUTH_044 로 바꾸고 실패 감사를 남기며 창을 비우지 않는다")
    void lockFailuresBecomeBusy(RuntimeException lockFailure) {
        passwordIs(true);
        given(adminOtpProcessor.findEnrollment(ADMIN_ID)).willReturn(SECRET);
        given(adminOtpProcessor.verifyEnrollmentCode(SECRET, "123456", NOW)).willReturn(99L);
        given(adminOtpProcessor.resolveProof(ADMIN_ID, "123456", NOW)).willReturn(PROOF);
        given(adminOtpProcessor.issueRecoveryCodes()).willReturn(ISSUED);
        willThrow(lockFailure).given(adminOtpProcessor).confirm(ADMIN_ID, SECRET, 99L, NOW, ISSUED.hashes());
        willThrow(lockFailure).given(adminOtpProcessor).disable(ADMIN_ID, PROOF, NOW);
        willThrow(lockFailure).given(adminOtpProcessor).replaceRecoveryCodes(ADMIN_ID, PROOF, ISSUED.hashes(), NOW);

        Throwable confirm = org.assertj.core.api.Assertions.catchThrowable(() -> facade.confirmEnrollment(ADMIN_ID, "current-password1", "123456"));
        Throwable disable = org.assertj.core.api.Assertions.catchThrowable(() -> facade.disable(ADMIN_ID, "current-password1", "123456"));
        Throwable regenerate = org.assertj.core.api.Assertions.catchThrowable(
            () -> facade.regenerateRecoveryCodes(ADMIN_ID, "current-password1", "123456"));

        for (Throwable thrown : List.of(confirm, disable, regenerate)) {
            assertCode(thrown, MemberErrorCode.OTP_BUSY);
            assertThat(thrown).hasCause(lockFailure);
        }
        verify(adminAuditProcessor).adminOtpEnrolled(ADMIN_ID, false);
        verify(adminAuditProcessor).adminOtpDisabled(ADMIN_ID, false);
        verify(adminAuditProcessor).adminOtpRecoveryCodesRegenerated(ADMIN_ID, false);
        verify(adminOtpProcessor, never()).discardEnrollment(anyLong());
        verify(loginGuardProcessor, never()).clearAdminOtp(anyLong());
    }
}
