package com.commercefoundry.domainlayer.member.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminAuditLogAdminItem;
import com.commercefoundry.domainlayer.member.domain.model.AdminAuditLog;
import com.commercefoundry.persistence.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/** 어드민 감사 로그 응답 (#192). 아이디를 문자열로 바꾼다 — 감사 칸에는 개인정보가 없어 마스킹하지 않는다. */
@Component
public class AdminAuditLogAdminPresenter {

    public PageResponse<AdminAuditLogAdminItem> toPageResponse(Page<AdminAuditLog> page) {
        return PageResponse.of(page).map(this::toItem);
    }

    private AdminAuditLogAdminItem toItem(AdminAuditLog log) {
        return AdminAuditLogAdminItem.builder()
            .auditLogId(String.valueOf(log.id()))
            .action(log.action())
            .actorMemberId(log.actorMemberId() == null ? null : String.valueOf(log.actorMemberId()))
            .target(log.target())
            .result(log.result())
            .createdAt(log.createdAt())
            .build();
    }
}
