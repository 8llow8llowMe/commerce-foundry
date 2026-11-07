package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.application.port.in.AdminPasswordResetAdminUseCase;
import com.commercefoundry.domainlayer.member.application.port.in.AdminPasswordResetWebUseCase;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminPasswordResetProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminPasswordResetAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;

/** 링크 소비·SMTP·BCrypt는 트랜잭션 밖, 잠금 저장·필수 컷오프는 Processor 트랜잭션 안에서 처리한다. */
@Service
@RequiredArgsConstructor
public class AdminPasswordResetFacade implements AdminPasswordResetAdminUseCase, AdminPasswordResetWebUseCase {
    private final AdminPasswordResetProcessor adminPasswordResetProcessor;
    private final AdminPasswordResetAccountProcessor adminPasswordResetAccountProcessor;
    private final AdminAccountProcessor adminAccountProcessor;
    private final AdminAuditProcessor adminAuditProcessor;

    @Override
    public void send(long actorMemberId, long targetMemberId) {
        try {
            adminPasswordResetProcessor.limitSend(actorMemberId, targetMemberId);
            var recipient = adminPasswordResetAccountProcessor.recipient(actorMemberId, targetMemberId);
            if (recipient.isPresent()) {
                try {
                    adminPasswordResetProcessor.send(recipient.get());
                } catch (MemberException unavailable) {
                    if (unavailable.getErrorCode() != MemberErrorCode.ADMIN_PASSWORD_RESET_UNAVAILABLE) { throw unavailable; }
                    // SMTP 장애에도 계정 존재 여부를 응답으로 노출하지 않는다. 실패 링크는 Processor가 폐기했다.
                    adminAuditProcessor.adminPasswordResetLinkSent(actorMemberId, targetMemberId, false);
                    return;
                }
            }
            adminAuditProcessor.adminPasswordResetLinkSent(actorMemberId, targetMemberId, true);
        } catch (RuntimeException failure) {
            adminAuditProcessor.adminPasswordResetLinkSent(actorMemberId, targetMemberId, false);
            throw failure;
        }
    }

    @Override
    public void reset(String token, String newPassword, String clientSubject) {
        var grant = adminPasswordResetProcessor.consume(token, clientSubject);
        try {
            String hash = adminAccountProcessor.hashPassword(newPassword);
            adminPasswordResetAccountProcessor.reset(grant, hash);
        } catch (QueryTimeoutException | TransactionTimedOutException | PessimisticLockingFailureException contention) {
            adminAuditProcessor.adminPasswordResetUsed(grant.memberId(), false);
            // 링크는 이미 소비했다. 409 후 같은 링크를 재생하지 않고 새 링크로 다시 시작한다.
            throw new MemberException(MemberErrorCode.ADMIN_PASSWORD_RESET_BUSY, contention);
        } catch (RuntimeException failure) {
            adminAuditProcessor.adminPasswordResetUsed(grant.memberId(), false);
            throw failure;
        }
        adminAuditProcessor.adminPasswordResetUsed(grant.memberId(), true);
    }
}
