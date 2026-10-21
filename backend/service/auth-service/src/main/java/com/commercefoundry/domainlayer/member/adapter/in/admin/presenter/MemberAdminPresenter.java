package com.commercefoundry.domainlayer.member.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminMemberItem;
import com.commercefoundry.domainlayer.member.adapter.in.support.MemberMasking;
import com.commercefoundry.domainlayer.member.application.info.AdminMemberInfo;
import com.commercefoundry.persistence.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * 어드민 회원 목록 응답 (#6). 아이디를 문자열로 바꾸고 개인정보를 마스킹한다 — 마스킹은 응답 모양의 일이라 여기서 한다(Info 는 원문).
 * 규칙은 {@link MemberMasking} 이다.
 */
@Component
public class MemberAdminPresenter {

    public PageResponse<AdminMemberItem> toPageResponse(Page<AdminMemberInfo> page) {
        return PageResponse.of(page).map(this::toItem);
    }

    private AdminMemberItem toItem(AdminMemberInfo info) {
        return AdminMemberItem.builder()
            .memberId(String.valueOf(info.memberId()))
            .email(MemberMasking.maskEmail(info.email()))
            .name(MemberMasking.maskName(info.name()))
            .phone(MemberMasking.maskPhone(info.phone()))
            .role(info.role())
            .createdAt(info.createdAt())
            .build();
    }
}
