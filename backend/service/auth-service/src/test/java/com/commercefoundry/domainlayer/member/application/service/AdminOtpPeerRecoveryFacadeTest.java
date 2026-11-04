package com.commercefoundry.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditPort;
import com.commercefoundry.domainlayer.member.adapter.in.admin.presenter.AdminOtpAdminPresenter;
import com.commercefoundry.domainlayer.member.application.exception.LoginAttemptLimitedException;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpProof;
import com.commercefoundry.domainlayer.member.application.model.IssuedRecoveryCodes;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccessCutoffProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminOtpProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.LoginGuardProcessor;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

/** 동료 OTP 복구의 재인증·DB 교체·컷오프·감사 순서와 실패 경계. */
class AdminOtpPeerRecoveryFacadeTest {

    private static final long ACTOR = 7L;
    private static final long TARGET = 9L;
    private static final Instant NOW = Instant.parse("2026-10-29T03:00:10Z");
    private static final Member ACTOR_MEMBER = Member.restore(ACTOR, "actor@example.com", "hash", "행위자", null, MemberRole.ADMIN, 0L);
    private static final Member TARGET_MEMBER = Member.restore(TARGET, "target@example.com", "hash2", "대상", null, MemberRole.ADMIN, 0L);
    private static final AdminOtpProof PROOF = new AdminOtpProof.RecoveryCodeMatch(55L);
    private static final IssuedRecoveryCodes ISSUED = new IssuedRecoveryCodes(List.of("ABCDE-FGH23", "JKLMN-PQR45"), List.of("hash-a", "hash-b"));

    private final AdminAccountProcessor accounts = mock(AdminAccountProcessor.class);
    private final AdminOtpProcessor otp = mock(AdminOtpProcessor.class);
    private final LoginGuardProcessor guard = mock(LoginGuardProcessor.class);
    private final AdminAccessCutoffProcessor cutoff = mock(AdminAccessCutoffProcessor.class);
    private final AdminAuditProcessor audit = mock(AdminAuditProcessor.class);
    private final AdminOtpPeerRecoveryFacade facade = new AdminOtpPeerRecoveryFacade(accounts, otp, guard, cutoff, audit,
        new AdminOtpAdminPresenter(), Clock.fixed(NOW, ZoneOffset.UTC));

    private void validProof() {
        given(accounts.findActiveOperator(ACTOR)).willReturn(ACTOR_MEMBER);
        given(accounts.passwordMatches(ACTOR_MEMBER, "current-password1")).willReturn(true);
        given(otp.resolveProof(ACTOR, "ABCDE-FGH23", NOW)).willReturn(PROOF);
        given(accounts.findActiveOperator(TARGET)).willReturn(TARGET_MEMBER);
        given(otp.issueRecoveryCodes()).willReturn(ISSUED);
    }

    @Test
    @DisplayName("시도 세기 → 행위자 비밀번호·코드 → 대상 확인 → DB 교체 → 성공 감사 → 컷오프 후 새 코드만 돌려준다")
    void successOrder() {
        validProof();

        assertThat(facade.recover(ACTOR, TARGET, "current-password1", "ABCDE-FGH23").recoveryCodes())
            .containsExactly("ABCDE-FGH23", "JKLMN-PQR45");

        InOrder order = inOrder(guard, accounts, otp, audit, cutoff);
        order.verify(guard).admitAdminOtpPeerRecovery(ACTOR);
        order.verify(accounts).passwordMatches(ACTOR_MEMBER, "current-password1");
        order.verify(otp).resolveProof(ACTOR, "ABCDE-FGH23", NOW);
        order.verify(accounts).findActiveOperator(TARGET);
        order.verify(otp).issueRecoveryCodes();
        order.verify(otp).recoverPeer(ACTOR, TARGET, "hash", PROOF, ISSUED.hashes(), NOW);
        order.verify(audit).adminOtpPeerRecovered(ACTOR, TARGET, true);
        order.verify(cutoff).cutOff(TARGET, NOW.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(1));
    }

    @Test
    @DisplayName("본인 대상은 거절하고 실패 감사한다")
    void selfRejected() {
        assertThatThrownBy(() -> facade.recover(ACTOR, ACTOR, "current-password1", "123456"))
            .isInstanceOf(MemberException.class).extracting("errorCode").isEqualTo(MemberErrorCode.OTP_PEER_RECOVERY_SELF);
        verify(guard).admitAdminOtpPeerRecovery(ACTOR);
        verify(audit).adminOtpPeerRecovered(ACTOR, ACTOR, false);
        verifyNoInteractions(accounts, otp, cutoff);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 코드를 보거나 새 코드를 해시하지 않고 실패 감사한다")
    void wrongPassword() {
        given(accounts.findActiveOperator(ACTOR)).willReturn(ACTOR_MEMBER);
        assertThatThrownBy(() -> facade.recover(ACTOR, TARGET, "bad-password", "123456"))
            .isInstanceOf(MemberException.class).extracting("errorCode").isEqualTo(MemberErrorCode.ADMIN_PASSWORD_MISMATCH);
        verify(audit).adminOtpPeerRecovered(ACTOR, TARGET, false);
        verifyNoInteractions(otp, cutoff);
    }

    @Test
    @DisplayName("시도 제한·Redis 계수기 장애는 인증과 감사 전에 중단한다")
    void limitedBeforeProof() {
        LoginAttemptLimitedException limited = new LoginAttemptLimitedException(MemberErrorCode.OTP_ATTEMPT_LIMITED, Duration.ofMinutes(2));
        willThrow(limited).given(guard).admitAdminOtpPeerRecovery(ACTOR);

        assertThatThrownBy(() -> facade.recover(ACTOR, TARGET, "current-password1", "123456")).isSameAs(limited);
        verifyNoInteractions(accounts, otp, audit, cutoff);
    }

    @Test
    @DisplayName("DB 교체 뒤 컷오프가 실패해도 교체 성공을 감사하고 503을 돌려준다 — 코드는 응답하지 않는다")
    void cutoffFailureAfterCommittedReset() {
        validProof();
        MemberException unavailable = new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        willThrow(unavailable).given(cutoff).cutOff(TARGET, NOW.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(1));

        assertThatThrownBy(() -> facade.recover(ACTOR, TARGET, "current-password1", "ABCDE-FGH23")).isSameAs(unavailable);
        verify(otp).recoverPeer(ACTOR, TARGET, "hash", PROOF, ISSUED.hashes(), NOW);
        verify(audit).adminOtpPeerRecovered(ACTOR, TARGET, true);
        verify(audit, never()).adminOtpPeerRecovered(ACTOR, TARGET, false);
    }

    @Test
    @DisplayName("DB 잠금 대기 실패는 AUTH_044와 실패 감사이며 컷오프를 쓰지 않는다")
    void busyBeforeCommit() {
        validProof();
        willThrow(new MemberException(MemberErrorCode.OTP_BUSY)).given(otp)
            .recoverPeer(ACTOR, TARGET, "hash", PROOF, ISSUED.hashes(), NOW);

        assertThatThrownBy(() -> facade.recover(ACTOR, TARGET, "current-password1", "ABCDE-FGH23"))
            .isInstanceOf(MemberException.class).extracting("errorCode").isEqualTo(MemberErrorCode.OTP_BUSY);
        verify(audit).adminOtpPeerRecovered(ACTOR, TARGET, false);
        verifyNoInteractions(cutoff);
    }

    @Test
    void unavailableAdmissionHasNoAudit() {
        RuntimeException failure = new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        willThrow(failure).given(guard).admitAdminOtpPeerRecovery(ACTOR);
        assertThatThrownBy(() -> facade.recover(ACTOR, TARGET, "current-password1", "123456")).isSameAs(failure);
        verifyNoInteractions(accounts, otp, audit, cutoff);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void failedAuditNeverSkipsCutoffOrReplacesItsOriginalFailure(boolean cutoffFails) {
        validProof();
        AdminAuditPort port = mock(AdminAuditPort.class);
        willThrow(new IllegalStateException("audit-secret")).given(port).adminOtpPeerRecovered(ACTOR, TARGET, true);
        RuntimeException original = new IllegalStateException("cutoff-secret", new IllegalArgumentException("cause-secret"));
        if (cutoffFails) willThrow(original).given(cutoff).cutOff(TARGET, NOW.plusSeconds(1));
        AdminOtpPeerRecoveryFacade safe = new AdminOtpPeerRecoveryFacade(accounts, otp, guard, cutoff, new AdminAuditProcessor(port),
            new AdminOtpAdminPresenter(), Clock.fixed(NOW, ZoneOffset.UTC));
        Logger logger = (Logger) LoggerFactory.getLogger(AdminOtpPeerRecoveryFacade.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            if (cutoffFails) {
                assertThatThrownBy(() -> safe.recover(ACTOR, TARGET, "current-password1", "ABCDE-FGH23")).isSameAs(original);
                assertThat(appender.list).singleElement().satisfies(event -> {
                    assertThat(event.getFormattedMessage()).contains("actorMemberId=7", "targetMemberId=9", "java.lang.IllegalStateException")
                        .doesNotContain("audit-secret", "cutoff-secret", "cause-secret");
                    assertThat(event.getThrowableProxy()).isNull();
                });
            } else {
                assertThat(safe.recover(ACTOR, TARGET, "current-password1", "ABCDE-FGH23").recoveryCodes()).containsExactlyElementsOf(ISSUED.codes());
            }
            verify(cutoff).cutOff(TARGET, NOW.plusSeconds(1));
            verify(port).adminOtpPeerRecovered(ACTOR, TARGET, true);
            org.mockito.Mockito.verifyNoMoreInteractions(port);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void failingDiagnosticLoggerCannotReplaceCutoffFailure() {
        validProof();
        RuntimeException original = new IllegalStateException("cutoff-secret");
        willThrow(original).given(cutoff).cutOff(TARGET, NOW.plusSeconds(1));
        Logger logger = (Logger) LoggerFactory.getLogger(AdminOtpPeerRecoveryFacade.class);
        Appender<ILoggingEvent> appender = mock(Appender.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("logger-secret")).when(appender).doAppend(org.mockito.ArgumentMatchers.any());
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> facade.recover(ACTOR, TARGET, "current-password1", "ABCDE-FGH23")).isSameAs(original);
            verify(audit).adminOtpPeerRecovered(ACTOR, TARGET, true);
            verify(audit, never()).adminOtpPeerRecovered(ACTOR, TARGET, false);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void presenterFailureDoesNotAddFailureAudit() {
        validProof();
        AdminOtpAdminPresenter presenter = mock(AdminOtpAdminPresenter.class);
        RuntimeException failure = new IllegalStateException("presenter-secret");
        given(presenter.toRecoveryCodes(org.mockito.ArgumentMatchers.any())).willThrow(failure);
        AdminOtpPeerRecoveryFacade withBrokenPresenter = new AdminOtpPeerRecoveryFacade(accounts, otp, guard, cutoff, audit,
            presenter, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> withBrokenPresenter.recover(ACTOR, TARGET, "current-password1", "ABCDE-FGH23")).isSameAs(failure);
        verify(audit).adminOtpPeerRecovered(ACTOR, TARGET, true);
        org.mockito.Mockito.verifyNoMoreInteractions(audit);
    }
}
