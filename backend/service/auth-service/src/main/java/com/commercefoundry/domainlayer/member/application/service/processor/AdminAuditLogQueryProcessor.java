package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.command.AdminAuditLogSearchCommand;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditLogRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.AdminAuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * 저장된 관리자 감사 로그 조회 (#192). 쓰기 쪽 {@link AdminAuditProcessor} 와 나눈다 — 그쪽은 트랜잭션 없이 업무 뒤에 부르는 것이 계약이다.
 * 트랜잭션은 부른 Facade 가 건다.
 */
@Component
@RequiredArgsConstructor
public class AdminAuditLogQueryProcessor {

    private final AdminAuditLogRepositoryPort adminAuditLogRepositoryPort;

    public Page<AdminAuditLog> search(AdminAuditLogSearchCommand command) {
        String action = command.action() == null ? null : command.action().name();
        return adminAuditLogRepositoryPort.findPage(action, command.actorMemberId(), command.page(), command.size());
    }
}
