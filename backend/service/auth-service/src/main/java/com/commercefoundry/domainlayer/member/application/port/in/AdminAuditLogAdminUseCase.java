package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminAuditLogAdminItem;
import com.commercefoundry.domainlayer.member.application.command.AdminAuditLogSearchCommand;
import com.commercefoundry.persistence.dto.PageResponse;

public interface AdminAuditLogAdminUseCase {

    /** 최신순 한 페이지. 조건에 맞는 기록이 없으면 빈 페이지다. 이 조회 자체는 감사하지 않는다(행에 개인정보가 없다). */
    PageResponse<AdminAuditLogAdminItem> searchAuditLogs(AdminAuditLogSearchCommand command);
}
