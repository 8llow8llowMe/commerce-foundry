package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpRecoveryCodesResponse;
import com.commercefoundry.domainlayer.member.adapter.in.admin.presenter.AdminOtpAdminPresenter;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpProof;
import com.commercefoundry.domainlayer.member.application.model.IssuedRecoveryCodes;
import com.commercefoundry.domainlayer.member.application.port.in.AdminOtpPeerRecoveryUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccessCutoffProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminOtpProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.LoginGuardProcessor;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * 동료 OTP 복구(#238 PR-C). BCrypt·Redis는 DB 트랜잭션 밖이다. Processor가 행위자 코드 소비와 대상 자격 교체만 짧은 트랜잭션으로 묶는다.
 * DB 커밋 뒤 컷오프 저장이 실패하면 503이지만 자격은 이미 바뀌었다. 재시도는 새 자격으로 다시 교체한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOtpPeerRecoveryFacade implements AdminOtpPeerRecoveryUseCase {

    private final AdminAccountProcessor adminAccountProcessor;
    private final AdminOtpProcessor adminOtpProcessor;
    private final LoginGuardProcessor loginGuardProcessor;
    private final AdminAccessCutoffProcessor adminAccessCutoffProcessor;
    private final AdminAuditProcessor adminAuditProcessor;
    private final AdminOtpAdminPresenter adminOtpAdminPresenter;
    private final Clock clock;

    @Override
    public AdminOtpRecoveryCodesResponse recover(long actorMemberId, long targetMemberId, String currentPassword, String code) {
        loginGuardProcessor.admitAdminOtpPeerRecovery(actorMemberId);
        if (actorMemberId == targetMemberId) {
            adminAuditProcessor.adminOtpPeerRecovered(actorMemberId, targetMemberId, false);
            throw new MemberException(MemberErrorCode.OTP_PEER_RECOVERY_SELF);
        }
        IssuedRecoveryCodes issued;
        try {
            Member actor = adminAccountProcessor.findActiveOperator(actorMemberId);
            if (!adminAccountProcessor.passwordMatches(actor, currentPassword)) {
                throw new MemberException(MemberErrorCode.ADMIN_PASSWORD_MISMATCH);
            }
            AdminOtpProof proof = adminOtpProcessor.resolveProof(actorMemberId, code, clock.instant());
            adminAccountProcessor.findActiveOperator(targetMemberId);
            issued = adminOtpProcessor.issueRecoveryCodes();
            inOtpQueue(() -> adminOtpProcessor.recoverPeer(actorMemberId, targetMemberId, actor.passwordHash(), proof,
                issued.hashes(), clock.instant()));
        } catch (RuntimeException failure) {
            adminAuditProcessor.adminOtpPeerRecovered(actorMemberId, targetMemberId, false);
            throw failure;
        }
        // 감사는 실제 DB 교체의 결과를 기록한다. 뒤의 Redis 컷오프가 실패해도 이미 교체한 자격을 실패로 기록하지 않는다.
        adminAuditProcessor.adminOtpPeerRecovered(actorMemberId, targetMemberId, true);
        try {
            Instant cutoff = clock.instant().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
            adminAccessCutoffProcessor.cutOff(targetMemberId, cutoff);
        } catch (RuntimeException failure) {
            try {
                log.error("admin otp peer recovery cutoff failed. actorMemberId={}, targetMemberId={}, error={}",
                    actorMemberId, targetMemberId, failure.getClass().getName());
            } catch (RuntimeException loggingFailure) {
                // 진단 로거 장애로 원래 컷오프 오류를 가리지 않는다.
            }
            throw failure;
        }
        return adminOtpAdminPresenter.toRecoveryCodes(issued.toInfo());
    }

    private static void inOtpQueue(Runnable work) {
        try {
            work.run();
        } catch (QueryTimeoutException | TransactionTimedOutException | PessimisticLockingFailureException exception) {
            throw new MemberException(MemberErrorCode.OTP_BUSY, exception);
        }
    }
}
