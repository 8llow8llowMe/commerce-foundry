package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpEnrollmentResponse;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpRecoveryCodesResponse;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpStatusResponse;
import com.commercefoundry.domainlayer.member.adapter.in.admin.presenter.AdminOtpAdminPresenter;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpProof;
import com.commercefoundry.domainlayer.member.application.model.IssuedRecoveryCodes;
import com.commercefoundry.domainlayer.member.application.port.in.AdminOtpUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminOtpProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.LoginGuardProcessor;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * 로그인한 관리자 본인의 OTP (#238 PR-A2). Facade 에 트랜잭션을 걸지 않는다 — BCrypt(비밀번호 확인 · 복구 코드 해시 · 비교)와 Redis(진행 중 등록 · 시도 창)를
 * 트랜잭션 밖에 두고, DB 쓰기만 Processor 의 짧은 트랜잭션으로 묶는다.
 *
 * <p>확인 · 해제 · 재발급의 순서는 같다: 시도 1회 세기(비밀번호가 틀려도 센다 — 429 {@code AUTH_043}) → 비밀번호(틀리면 {@code AUTH_032}) → 코드({@code AUTH_035}) →
 * 저장 트랜잭션 → 시도 창 비우기 → 성공 감사. 등록 확인만은 진행 중 등록({@code AUTH_038})을 비밀번호보다 먼저 본다 — 등록이 없으면 비밀번호를 확인할 이유가 없다.
 * 판정 전 실패(시도 제한 429 · 계수기 장애 503)는 감사하지 않는다 — 판정 전에 끊겨 기록할 행위가 없고, 반복으로 감사 행을 늘리지 않게(잠금 전환은 시도 제한 로그가 남긴다).
 * 그 밖의 실패는 실패 감사를 남기고 같은 예외를 다시 던진다. 응답은 비밀번호와 코드 중 어느 쪽이 틀렸는지만 나눈다.
 *
 * <p>저장 트랜잭션의 잠금 획득 실패({@link PessimisticLockingFailureException}) · 문장 취소({@link QueryTimeoutException}) · 상한 뒤 쿼리({@link TransactionTimedOutException})는
 * 같은 관리자의 다른 OTP 쓰기와 겹친 것이라 409 {@code AUTH_044} 로 바꾼다 — 어댑터가 잠금 대기 초과를 이미 바꾼 경우와 같은 응답이고, 실패 감사도 같이 남는다.
 *
 * <p>비밀번호를 요구하는 이유: access 토큰만 훔친 사람이 OTP 를 걸어 진짜 관리자를 잠그거나, 해제해 2단계를 끄지 못하게 한다.
 */
@Service
@RequiredArgsConstructor
public class AdminOtpFacade implements AdminOtpUseCase {

    private final AdminOtpProcessor adminOtpProcessor;
    private final AdminAccountProcessor adminAccountProcessor;
    private final LoginGuardProcessor loginGuardProcessor;
    private final AdminAuditProcessor adminAuditProcessor;
    private final AdminOtpAdminPresenter adminOtpAdminPresenter;
    private final Clock clock;

    @Override
    public AdminOtpStatusResponse getStatus(long adminMemberId) {
        return adminOtpAdminPresenter.toStatus(adminOtpProcessor.status(adminMemberId));
    }

    /** 감사하지 않는다 — 확인 전에는 아무것도 켜지지 않고, 진행 중 등록은 TTL 로 사라진다. 계정 라벨은 관리자 이메일이다(응답에만 싣는다). */
    @Override
    public AdminOtpEnrollmentResponse startEnrollment(long adminMemberId) {
        Member admin = adminAccountProcessor.findActiveAdmin(adminMemberId);
        return adminOtpAdminPresenter.toEnrollment(adminOtpProcessor.startEnrollment(adminMemberId, admin.email(), clock.instant()));
    }

    @Override
    public AdminOtpRecoveryCodesResponse confirmEnrollment(long adminMemberId, String currentPassword, String code) {
        loginGuardProcessor.admitAdminOtp(adminMemberId);
        IssuedRecoveryCodes issued;
        try {
            byte[] secret = adminOtpProcessor.findEnrollment(adminMemberId);
            requirePassword(adminMemberId, currentPassword);
            long step = adminOtpProcessor.verifyEnrollmentCode(secret, code, clock.instant());
            issued = adminOtpProcessor.issueRecoveryCodes();
            inOtpQueue(() -> adminOtpProcessor.confirm(adminMemberId, secret, step, clock.instant(), issued.hashes()));
        } catch (MemberException failure) {
            adminAuditProcessor.adminOtpEnrolled(adminMemberId, false);
            throw failure;
        }
        adminOtpProcessor.discardEnrollment(adminMemberId);
        loginGuardProcessor.clearAdminOtp(adminMemberId);
        adminAuditProcessor.adminOtpEnrolled(adminMemberId, true);
        return adminOtpAdminPresenter.toRecoveryCodes(issued.toInfo());
    }

    @Override
    public void disable(long adminMemberId, String currentPassword, String code) {
        loginGuardProcessor.admitAdminOtp(adminMemberId);
        try {
            requirePassword(adminMemberId, currentPassword);
            AdminOtpProof proof = adminOtpProcessor.resolveProof(adminMemberId, code, clock.instant());
            inOtpQueue(() -> adminOtpProcessor.disable(adminMemberId, proof, clock.instant()));
        } catch (MemberException failure) {
            adminAuditProcessor.adminOtpDisabled(adminMemberId, false);
            throw failure;
        }
        loginGuardProcessor.clearAdminOtp(adminMemberId);
        adminAuditProcessor.adminOtpDisabled(adminMemberId, true);
    }

    /** 새 코드의 해시(BCrypt 8번)는 두 번째 인자를 확인한 뒤에 만든다 — 틀린 요청이 해시 비용을 치르지 않게. */
    @Override
    public AdminOtpRecoveryCodesResponse regenerateRecoveryCodes(long adminMemberId, String currentPassword, String code) {
        loginGuardProcessor.admitAdminOtp(adminMemberId);
        IssuedRecoveryCodes issued;
        try {
            requirePassword(adminMemberId, currentPassword);
            AdminOtpProof proof = adminOtpProcessor.resolveProof(adminMemberId, code, clock.instant());
            issued = adminOtpProcessor.issueRecoveryCodes();
            inOtpQueue(() -> adminOtpProcessor.replaceRecoveryCodes(adminMemberId, proof, issued.hashes(), clock.instant()));
        } catch (MemberException failure) {
            adminAuditProcessor.adminOtpRecoveryCodesRegenerated(adminMemberId, false);
            throw failure;
        }
        loginGuardProcessor.clearAdminOtp(adminMemberId);
        adminAuditProcessor.adminOtpRecoveryCodesRegenerated(adminMemberId, true);
        return adminOtpAdminPresenter.toRecoveryCodes(issued.toInfo());
    }

    /** Processor 의 저장 트랜잭션을 부른다. 잠금 실패 · 시간 상한 초과는 409 {@code AUTH_044} 다 — 트랜잭션은 예외로 롤백된다. */
    private static void inOtpQueue(Runnable work) {
        try {
            work.run();
        } catch (QueryTimeoutException | TransactionTimedOutException | PessimisticLockingFailureException exception) {
            throw new MemberException(MemberErrorCode.OTP_BUSY, exception);
        }
    }

    /** 활성 관리자가 아니면 {@code AUTH_033}(게이트웨이 컷오프를 지난 드문 경우), 비밀번호가 틀리면 {@code AUTH_032}. BCrypt 는 트랜잭션 밖이다. */
    private void requirePassword(long adminMemberId, String currentPassword) {
        Member admin = adminAccountProcessor.findActiveAdmin(adminMemberId);
        if (!adminAccountProcessor.passwordMatches(admin, currentPassword)) {
            throw new MemberException(MemberErrorCode.ADMIN_PASSWORD_MISMATCH);
        }
    }
}
