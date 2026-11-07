package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.application.port.in.AdminPasswordResetAdminUseCase;
import com.commercefoundry.domainlayer.member.application.port.in.AdminPasswordResetWebUseCase;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminPasswordResetProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminPasswordResetAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccessCutoffProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Redis·SMTP·BCrypt는 트랜잭션 밖에서 실행하고, 잠금 저장만 Processor 트랜잭션으로 좁힌다. */
@Service
@RequiredArgsConstructor
public class AdminPasswordResetFacade implements AdminPasswordResetAdminUseCase, AdminPasswordResetWebUseCase {
    private final AdminPasswordResetProcessor adminPasswordResetProcessor;
    private final AdminPasswordResetAccountProcessor adminPasswordResetAccountProcessor;
    private final AdminAccountProcessor adminAccountProcessor;
    private final AdminAccessCutoffProcessor adminAccessCutoffProcessor;
    private final AdminAuditProcessor adminAuditProcessor;
    private final Clock clock;

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
            // DB는 이미 커밋됐다. 컷오프가 실패해도 링크는 소진되어 같은 링크로 복구할 수 없다.
            // 새 링크로 다시 재설정하거나 별도 컷오프가 필요하다. 그때까지 옛 access는 자연 만료 전 살아 있을 수 있다.
            adminAccessCutoffProcessor.cutOff(grant.memberId(), clock.instant().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1));
        } catch (RuntimeException failure) {
            adminAuditProcessor.adminPasswordResetUsed(grant.memberId(), false);
            throw failure;
        }
        adminAuditProcessor.adminPasswordResetUsed(grant.memberId(), true);
    }
}
