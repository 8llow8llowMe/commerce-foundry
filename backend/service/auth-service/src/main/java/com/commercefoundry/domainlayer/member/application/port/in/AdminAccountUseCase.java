package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminAccountCreatedResponse;

/**
 * 가게 운영자 계정 (#193). 호출자는 로그인한 관리자고, 행위자 아이디는 access 토큰의 memberId 만 쓴다.
 */
public interface AdminAccountUseCase {

    /** 만든 운영자. memberId 는 문자열이다. */
    AdminAccountCreatedResponse createAdmin(long actorMemberId, String email, String rawPassword, String name);

    void deactivateAdmin(long actorMemberId, long targetMemberId, String currentTokenId);

    void changePassword(long actorMemberId, String currentTokenId, String currentPassword, String newPassword);
}
