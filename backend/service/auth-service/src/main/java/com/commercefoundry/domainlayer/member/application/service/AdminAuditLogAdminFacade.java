package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminAuditLogAdminItem;
import com.commercefoundry.domainlayer.member.adapter.in.admin.presenter.AdminAuditLogAdminPresenter;
import com.commercefoundry.domainlayer.member.application.command.AdminAuditLogSearchCommand;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAuditLogAdminUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditLogQueryProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberNameQueryProcessor;
import com.commercefoundry.domainlayer.member.domain.model.AdminAuditLog;
import com.commercefoundry.persistence.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAuditLogAdminFacade implements AdminAuditLogAdminUseCase {

    private final AdminAuditLogQueryProcessor adminAuditLogQueryProcessor;
    private final MemberNameQueryProcessor memberNameQueryProcessor;
    private final AdminAuditLogAdminPresenter adminAuditLogAdminPresenter;

    /** 감사 로그와 그 페이지의 회원 이름을 같은 읽기 트랜잭션에서 읽는다. 이름 조회는 페이지당 한 번이다. */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminAuditLogAdminItem> searchAuditLogs(AdminAuditLogSearchCommand command) {
        Page<AdminAuditLog> logs = adminAuditLogQueryProcessor.search(command);
        return adminAuditLogAdminPresenter.toPageResponse(logs, memberNameQueryProcessor.findNamesByIds(AdminAuditLog.referencedMemberIds(logs.getContent())));
    }
}
