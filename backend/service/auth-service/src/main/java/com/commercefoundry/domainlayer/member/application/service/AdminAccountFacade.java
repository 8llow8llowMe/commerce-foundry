package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminAccountItem;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminAccountCreatedResponse;
import com.commercefoundry.domainlayer.member.adapter.in.admin.presenter.AdminAccountPresenter;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAccountUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccessCutoffProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccountProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAccountQueryProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.principal.MemberRole;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 가게 운영자 계정 (#193 · #236). 순서: 해시(트랜잭션 밖) → 저장 → 옛 access 토큰 컷오프 → 감사.
 * 컷오프 저장(Redis)은 DB 트랜잭션 밖의 외부 I/O 라 Facade 에 트랜잭션을 걸지 않고 저장 트랜잭션을 Processor 로 좁힌다.
 * 컷오프 저장이 실패하면 계정 변경은 이미 반영된 채 503 이다 — 같은 요청을 다시 보내면 같은 값 · 같은 상태라도 컷오프를 다시 써 복구한다.
 * 감사 성공 행은 컷오프까지 끝난 뒤에 남긴다.
 */
@Service
@RequiredArgsConstructor
public class AdminAccountFacade implements AdminAccountUseCase {

    private final AdminAccountProcessor adminAccountProcessor;
    private final AdminAccountQueryProcessor adminAccountQueryProcessor;
    private final AdminAccessCutoffProcessor adminAccessCutoffProcessor;
    private final AdminAuditProcessor adminAuditProcessor;
    private final AdminAccountPresenter adminAccountPresenter;
    private final Clock clock;

    @Override
    public AdminAccountCreatedResponse createAdmin(long actorMemberId, String email, String rawPassword, String name, MemberRole role) {
        Member created;
        try {
            String normalizedEmail = Member.normalizeEmail(email);
            String passwordHash = adminAccountProcessor.hashPassword(rawPassword);
            created = adminAccountProcessor.create(actorMemberId, normalizedEmail, passwordHash, name, role);
        } catch (RuntimeException exception) {
            adminAuditProcessor.adminAccountCreated(actorMemberId, null, false);
            throw exception;
        }
        adminAuditProcessor.adminAccountCreated(actorMemberId, created.id(), true);
        return adminAccountPresenter.toCreated(created.id());
    }

    @Override
    public PageResponse<AdminAccountItem> searchAdmins(long actorMemberId, Boolean active, int page, int size) {
        PageResponse<AdminAccountItem> response = adminAccountPresenter.toPageResponse(adminAccountQueryProcessor.search(active, page, size));
        adminAuditProcessor.operatorsSearched(actorMemberId, active, page, size);
        return response;
    }

    @Override
    public void deactivateAdmin(long actorMemberId, long targetMemberId, String currentTokenId) {
        try {
            adminAccountProcessor.deactivate(actorMemberId, targetMemberId, clock.instant());
            adminAccessCutoffProcessor.cutOff(targetMemberId, cutoff());
            if (actorMemberId == targetMemberId) {
                adminAccessCutoffProcessor.revokeCurrent(currentTokenId);
            }
        } catch (RuntimeException exception) {
            adminAuditProcessor.adminAccountDeactivated(actorMemberId, targetMemberId, false);
            throw exception;
        }
        adminAuditProcessor.adminAccountDeactivated(actorMemberId, targetMemberId, true);
    }

    @Override
    public void changeRole(long actorMemberId, long targetMemberId, MemberRole role) {
        try {
            adminAccountProcessor.changeRole(actorMemberId, targetMemberId, role);
            adminAccessCutoffProcessor.cutOff(targetMemberId, cutoff());
        } catch (RuntimeException exception) {
            adminAuditProcessor.adminRoleChanged(actorMemberId, targetMemberId, false);
            throw exception;
        }
        adminAuditProcessor.adminRoleChanged(actorMemberId, targetMemberId, true);
    }

    @Override
    public void changeStatus(long actorMemberId, long targetMemberId, boolean active) {
        try {
            adminAccountProcessor.changeStatus(actorMemberId, targetMemberId, active, clock.instant());
            if (!active) {
                adminAccessCutoffProcessor.cutOff(targetMemberId, cutoff());
            }
        } catch (RuntimeException exception) {
            auditStatusChange(actorMemberId, targetMemberId, active, false);
            throw exception;
        }
        auditStatusChange(actorMemberId, targetMemberId, active, true);
    }

    @Override
    public void changePassword(long actorMemberId, String currentTokenId, String currentPassword, String newPassword) {
        try {
            Member admin = adminAccountProcessor.findActiveOperator(actorMemberId);
            if (!adminAccountProcessor.passwordMatches(admin, currentPassword)) {
                throw new MemberException(MemberErrorCode.ADMIN_PASSWORD_MISMATCH);
            }
            String passwordHash = adminAccountProcessor.hashPassword(newPassword);
            adminAccountProcessor.changePassword(actorMemberId, passwordHash);
            adminAccessCutoffProcessor.cutOff(actorMemberId, cutoff());
            adminAccessCutoffProcessor.revokeCurrent(currentTokenId);
        } catch (RuntimeException exception) {
            adminAuditProcessor.adminPasswordChanged(actorMemberId, false);
            throw exception;
        }
        adminAuditProcessor.adminPasswordChanged(actorMemberId, true);
    }

    private void auditStatusChange(long actorMemberId, long targetMemberId, boolean active, boolean succeeded) {
        if (active) {
            adminAuditProcessor.adminAccountReactivated(actorMemberId, targetMemberId, succeeded);
        } else {
            adminAuditProcessor.adminAccountDeactivated(actorMemberId, targetMemberId, succeeded);
        }
    }

    /** 지금 초를 포함해 이미 발급된 토큰은 거절되고, 다음 초에 다시 로그인하면 통과한다. */
    private Instant cutoff() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
    }
}
