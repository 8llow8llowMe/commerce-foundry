package com.commercefoundry.domainlayer.member.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminAuditLogAdminItem;
import com.commercefoundry.domainlayer.member.adapter.in.support.MemberMasking;
import com.commercefoundry.domainlayer.member.domain.model.AdminAuditLog;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/** 어드민 감사 로그 응답 (#192 · #322). 아이디는 문자열로, 이름은 여기서만 마스킹한다. 이름 원문은 응답에 싣지 않는다. */
@Component
public class AdminAuditLogAdminPresenter {

    public PageResponse<AdminAuditLogAdminItem> toPageResponse(Page<AdminAuditLog> page, Map<Long, String> namesByMemberId) {
        return PageResponse.of(page).map(log -> toItem(log, namesByMemberId));
    }

    private AdminAuditLogAdminItem toItem(AdminAuditLog log, Map<Long, String> namesByMemberId) {
        return AdminAuditLogAdminItem.builder()
            .auditLogId(String.valueOf(log.id()))
            .action(log.action())
            .actorMemberId(log.actorMemberId() == null ? null : String.valueOf(log.actorMemberId()))
            .actorDisplayName(displayName(namesByMemberId, log.actorMemberId()))
            .target(log.target())
            .targetDisplayName(displayName(namesByMemberId, log.memberTargetId()))
            .result(log.result())
            .createdAt(log.createdAt())
            .build();
    }

    /** 회원이 없거나 이름이 없으면 null. {@code maskName(null)} 은 {@code *} 라 이름이 없을 때는 마스킹하지 않는다. */
    private static String displayName(Map<Long, String> namesByMemberId, Long memberId) {
        if (memberId == null || !namesByMemberId.containsKey(memberId)) {
            return null;
        }
        return MemberMasking.maskNameIfPresent(namesByMemberId.get(memberId));
    }
}
