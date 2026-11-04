package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.model.AdminAuditAction;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 관리자 감사 기록 (#6, {@code security-guide.md} §11-4). Facade 는 out-port({@link AdminAuditPort})를 직접 부르지 않고 이 Processor 를 거친다
 * (ArchUnit H11). "실패 로그에 무엇을 대상으로 남기나" 같은 감사 정책도 여기에 모은다. 트랜잭션을 걸지 않는다 — 업무가 끝난 뒤 부른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAuditProcessor {

    private final AdminAuditPort adminAuditPort;

    public void adminLoginSucceeded(long adminMemberId) {
        adminAuditPort.adminLoginSucceeded(adminMemberId);
    }

    /** OTP 등록 관리자의 로그인 1단계 통과 (#238 PR-B) — 로그인 성공과 다른 행위({@code ADMIN_LOGIN_PASSWORD_VERIFIED})다. */
    public void adminLoginPasswordVerified(long adminMemberId) {
        adminAuditPort.adminLoginPasswordVerified(adminMemberId);
    }

    /** 노린 계정이 관리자일 때만 그 memberId 를 대상으로 남긴다 — 고객 memberId 는 관리자 감사 로그에 남기지 않는다. */
    public void adminLoginFailed(Optional<Member> candidate) {
        OptionalLong target = candidate.filter(Member::isOperator).map(member -> OptionalLong.of(member.id())).orElse(OptionalLong.empty());
        adminAuditPort.adminLoginFailed(target);
    }

    /**
     * 관리자 로그인 2단계 실패 (#238 PR-B) — 챌린지로 대상 관리자를 안 뒤의 실패다. 1단계 실패와 같은 {@code ADMIN_LOGIN} 실패(대상 {@code member:<id>})로 남긴다.
     * 코드가 TOTP 였는지 복구 코드였는지는 싣지 않는다.
     */
    public void adminLoginSecondFactorFailed(long adminMemberId) {
        adminAuditPort.adminLoginFailed(OptionalLong.of(adminMemberId));
    }

    /** @param role 역할 조건. 없으면 null(전체) */
    public void membersSearched(long adminMemberId, MemberRole role, int page, int size) {
        adminAuditPort.membersSearched(adminMemberId, role, page, size);
    }

    public void adminAccountCreated(long actorMemberId, Long createdMemberId, boolean succeeded) {
        safelyRecord(AdminAuditAction.ADMIN_CREATE, actorMemberId, () -> adminAuditPort.adminAccountCreated(actorMemberId, createdMemberId, succeeded));
    }

    public void adminAccountDeactivated(long actorMemberId, long targetMemberId, boolean succeeded) {
        safelyRecord(AdminAuditAction.ADMIN_DEACTIVATE, actorMemberId, () -> adminAuditPort.adminAccountDeactivated(actorMemberId, targetMemberId, succeeded));
    }

    public void adminPasswordChanged(long adminMemberId, boolean succeeded) {
        safelyRecord(AdminAuditAction.ADMIN_PASSWORD_CHANGE, adminMemberId, () -> adminAuditPort.adminPasswordChanged(adminMemberId, succeeded));
    }

    public void adminOtpEnrolled(long adminMemberId, boolean succeeded) {
        safelyRecord(AdminAuditAction.ADMIN_OTP_ENROLL, adminMemberId, () -> adminAuditPort.adminOtpEnrolled(adminMemberId, succeeded));
    }

    public void adminOtpDisabled(long adminMemberId, boolean succeeded) {
        safelyRecord(AdminAuditAction.ADMIN_OTP_DISABLE, adminMemberId, () -> adminAuditPort.adminOtpDisabled(adminMemberId, succeeded));
    }

    public void adminOtpRecoveryCodesRegenerated(long adminMemberId, boolean succeeded) {
        safelyRecord(AdminAuditAction.ADMIN_OTP_RECOVERY_REGENERATE, adminMemberId, () -> adminAuditPort.adminOtpRecoveryCodesRegenerated(adminMemberId, succeeded));
    }

    public void adminOtpPeerRecovered(long actorMemberId, long targetMemberId, boolean succeeded) {
        safelyRecord(AdminAuditAction.ADMIN_OTP_PEER_RECOVERY, actorMemberId, () -> adminAuditPort.adminOtpPeerRecovered(actorMemberId, targetMemberId, succeeded));
    }

    /** 계정·OTP 감사 장애를 격리한다. 업무 결과와 호출자에게 남은 필수 처리를 바꾸지 않는다. */
    private void safelyRecord(AdminAuditAction action, long actorMemberId, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException exception) {
            try {
                log.error("admin audit failed. action={}, actorMemberId={}, error={}", action, actorMemberId, exception.getClass().getName());
            } catch (RuntimeException loggingFailure) {
                // 진단 로거 장애도 이미 끝난 업무 결과를 바꾸지 않는다.
            }
        }
    }
}
