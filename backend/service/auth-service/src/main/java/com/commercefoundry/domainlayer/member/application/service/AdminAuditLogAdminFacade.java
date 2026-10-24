package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminAuditLogAdminItem;
import com.commercefoundry.domainlayer.member.adapter.in.admin.presenter.AdminAuditLogAdminPresenter;
import com.commercefoundry.domainlayer.member.application.command.AdminAuditLogSearchCommand;
import com.commercefoundry.domainlayer.member.application.port.in.AdminAuditLogAdminUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditLogQueryProcessor;
import com.commercefoundry.persistence.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAuditLogAdminFacade implements AdminAuditLogAdminUseCase {

    private final AdminAuditLogQueryProcessor adminAuditLogQueryProcessor;
    private final AdminAuditLogAdminPresenter adminAuditLogAdminPresenter;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminAuditLogAdminItem> searchAuditLogs(AdminAuditLogSearchCommand command) {
        return adminAuditLogAdminPresenter.toPageResponse(adminAuditLogQueryProcessor.search(command));
    }
}
