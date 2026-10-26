package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminAuditLogAdminItem;
import com.commercefoundry.domainlayer.member.application.command.AdminAuditLogSearchCommand;
import com.commercefoundry.persistence.dto.PageResponse;

public interface AdminAuditLogAdminUseCase {

    /**
     * 최신순 한 페이지. 조건에 맞는 기록이 없으면 빈 페이지다.
     * 이 조회 자체는 감사하지 않는다 — 저장된 행에 개인정보 원문이 없고, 남기면 조회마다 행이 늘어난다.
     * 표시 이름은 이 요청 안에서 회원 이름을 한 번에 읽어 마스킹한다. 어드민 회원 목록 API 는 쓰지 않는다.
     */
    PageResponse<AdminAuditLogAdminItem> searchAuditLogs(AdminAuditLogSearchCommand command);
}
