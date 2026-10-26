package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 관리자 감사 기록 (#6, {@code security-guide.md} §11-4). Facade 는 out-port({@link AdminAuditPort})를 직접 부르지 않고 이 Processor 를 거친다
 * (ArchUnit H11). "실패 로그에 무엇을 대상으로 남기나" 같은 감사 정책도 여기에 모은다. 트랜잭션을 걸지 않는다 — 업무가 끝난 뒤 부른다.
 */
@Component
@RequiredArgsConstructor
public class AdminAuditProcessor {

    private final AdminAuditPort adminAuditPort;

    public void adminLoginSucceeded(long adminMemberId) {
        adminAuditPort.adminLoginSucceeded(adminMemberId);
    }

    /** 노린 계정이 관리자일 때만 그 memberId 를 대상으로 남긴다 — 고객 memberId 는 관리자 감사 로그에 남기지 않는다. */
    public void adminLoginFailed(Optional<Member> candidate) {
        OptionalLong target = candidate.filter(Member::isAdmin).map(member -> OptionalLong.of(member.id())).orElse(OptionalLong.empty());
        adminAuditPort.adminLoginFailed(target);
    }

    /** @param role 역할 조건. 없으면 null(전체) */
    public void membersSearched(long adminMemberId, MemberRole role, int page, int size) {
        adminAuditPort.membersSearched(adminMemberId, role, page, size);
    }

    public void adminAccountCreated(long actorMemberId, Long createdMemberId, boolean succeeded) {
        adminAuditPort.adminAccountCreated(actorMemberId, createdMemberId, succeeded);
    }

    public void adminAccountDeactivated(long actorMemberId, long targetMemberId, boolean succeeded) {
        adminAuditPort.adminAccountDeactivated(actorMemberId, targetMemberId, succeeded);
    }

    public void adminPasswordChanged(long adminMemberId, boolean succeeded) {
        adminAuditPort.adminPasswordChanged(adminMemberId, succeeded);
    }
}
