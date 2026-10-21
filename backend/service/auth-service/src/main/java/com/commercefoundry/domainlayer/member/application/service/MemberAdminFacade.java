package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminMemberItem;
import com.commercefoundry.domainlayer.member.adapter.in.admin.presenter.MemberAdminPresenter;
import com.commercefoundry.domainlayer.member.application.port.in.MemberAdminUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberAdminProcessor;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.principal.MemberRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 어드민 회원 관리 (#6). 읽기 트랜잭션은 {@link MemberAdminProcessor} 가 건다 — 감사 로그({@link AdminAuditProcessor})는 조회가 끝난 뒤 트랜잭션 밖에서 쓴다.
 */
@Service
@RequiredArgsConstructor
public class MemberAdminFacade implements MemberAdminUseCase {

    private final MemberAdminProcessor memberAdminProcessor;
    private final AdminAuditProcessor adminAuditProcessor;
    private final MemberAdminPresenter memberAdminPresenter;

    @Override
    public PageResponse<AdminMemberItem> searchMembers(long adminMemberId, MemberRole role, int page, int size) {
        PageResponse<AdminMemberItem> response = memberAdminPresenter.toPageResponse(memberAdminProcessor.search(role, page, size));
        adminAuditProcessor.membersSearched(adminMemberId, role, page, size);
        return response;
    }
}
