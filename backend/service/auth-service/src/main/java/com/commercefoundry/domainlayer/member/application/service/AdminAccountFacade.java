package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminAccountCreatedResponse;
import com.commercefoundry.domainlayer.member.adapter.in.admin.presenter.AdminAccountPresenter;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAccountUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccessCutoffProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 가게 운영자 계정 (#193). 순서: 해시(트랜잭션 밖) → 저장 → 옛 access 토큰 컷오프 → 감사.
 * 컷오프 저장이 실패하면 계정 변경은 이미 반영된 채 503 이다. 감사 성공 행은 컷오프까지 끝난 뒤에 남긴다.
 */
@Service
@RequiredArgsConstructor
public class AdminAccountFacade implements AdminAccountUseCase {

    private final AdminAccountProcessor adminAccountProcessor;
    private final AdminAccessCutoffProcessor adminAccessCutoffProcessor;
    private final AdminAuditProcessor adminAuditProcessor;
    private final AdminAccountPresenter adminAccountPresenter;
    private final Clock clock;

    @Override
    public AdminAccountCreatedResponse createAdmin(long actorMemberId, String email, String rawPassword, String name) {
        String normalizedEmail = Member.normalizeEmail(email);
        String passwordHash = adminAccountProcessor.hashPassword(rawPassword);
        try {
            Member created = adminAccountProcessor.create(normalizedEmail, passwordHash, name);
            adminAuditProcessor.adminAccountCreated(actorMemberId, created.id(), true);
            return adminAccountPresenter.toCreated(created.id());
        } catch (MemberException exception) {
            adminAuditProcessor.adminAccountCreated(actorMemberId, null, false);
            throw exception;
        }
    }

    @Override
    public void deactivateAdmin(long actorMemberId, long targetMemberId, String currentTokenId) {
        try {
            adminAccountProcessor.deactivate(targetMemberId, clock.instant());
        } catch (MemberException exception) {
            adminAuditProcessor.adminAccountDeactivated(actorMemberId, targetMemberId, false);
            throw exception;
        }
        adminAccessCutoffProcessor.cutOff(targetMemberId, cutoff());
        if (actorMemberId == targetMemberId) {
            adminAccessCutoffProcessor.revokeCurrent(currentTokenId);
        }
        adminAuditProcessor.adminAccountDeactivated(actorMemberId, targetMemberId, true);
    }

    @Override
    public void changePassword(long actorMemberId, String currentTokenId, String currentPassword, String newPassword) {
        Member admin = adminAccountProcessor.findActiveAdmin(actorMemberId);
        if (!adminAccountProcessor.passwordMatches(admin, currentPassword)) {
            adminAuditProcessor.adminPasswordChanged(actorMemberId, false);
            throw new MemberException(MemberErrorCode.ADMIN_PASSWORD_MISMATCH);
        }
        String passwordHash = adminAccountProcessor.hashPassword(newPassword);
        try {
            adminAccountProcessor.changePassword(actorMemberId, passwordHash);
        } catch (MemberException exception) {
            adminAuditProcessor.adminPasswordChanged(actorMemberId, false);
            throw exception;
        }
        adminAccessCutoffProcessor.cutOff(actorMemberId, cutoff());
        adminAccessCutoffProcessor.revokeCurrent(currentTokenId);
        adminAuditProcessor.adminPasswordChanged(actorMemberId, true);
    }

    /** 지금 초를 포함해 이미 발급된 토큰은 거절되고, 다음 초에 다시 로그인하면 통과한다. */
    private Instant cutoff() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
    }
}
