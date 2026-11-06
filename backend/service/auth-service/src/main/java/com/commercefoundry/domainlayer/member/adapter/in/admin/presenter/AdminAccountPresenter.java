package com.commercefoundry.domainlayer.member.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminAccountItem;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminAccountCreatedResponse;
import com.commercefoundry.domainlayer.member.adapter.in.support.MemberMasking;
import com.commercefoundry.domainlayer.member.application.info.AdminAccountInfo;
import com.commercefoundry.persistence.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/** 운영자 계정 응답 (#193 · #236). 아이디를 문자열로 바꾸고, 목록의 이메일 · 이름은 {@link MemberMasking} 규칙으로 마스킹한다(Info 는 원문). */
@Component
public class AdminAccountPresenter {

    public AdminAccountCreatedResponse toCreated(long memberId) {
        return AdminAccountCreatedResponse.builder().memberId(String.valueOf(memberId)).build();
    }

    public PageResponse<AdminAccountItem> toPageResponse(Page<AdminAccountInfo> page) {
        return PageResponse.of(page).map(this::toItem);
    }

    private AdminAccountItem toItem(AdminAccountInfo info) {
        return AdminAccountItem.builder()
            .memberId(String.valueOf(info.memberId()))
            .email(MemberMasking.maskEmail(info.email()))
            .name(MemberMasking.maskName(info.name()))
            .role(info.role())
            .active(info.active())
            .deactivatedAt(info.deactivatedAt())
            .otpEnrolled(info.otpEnrolled())
            .createdAt(info.createdAt())
            .build();
    }
}
