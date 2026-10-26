package com.commercefoundry.domainlayer.member.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminAccountCreatedResponse;
import org.springframework.stereotype.Component;

/** 관리자 아이디를 문자열로 바꾼다 (#193). */
@Component
public class AdminAccountPresenter {

    public AdminAccountCreatedResponse toCreated(long memberId) {
        return AdminAccountCreatedResponse.builder().memberId(String.valueOf(memberId)).build();
    }
}
