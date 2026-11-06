package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminAccountItem;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminAccountCreatedResponse;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.principal.MemberRole;

/**
 * 가게 운영자 계정 (#193 · #236). 호출자는 로그인한 관리자고, 행위자 아이디는 access 토큰의 memberId 만 쓴다.
 */
public interface AdminAccountUseCase {

    /**
     * 만든 운영자. memberId 는 문자열이다.
     *
     * @param role OWNER · MANAGER · STAFF 중 하나. null 이면 ADMIN
     */
    AdminAccountCreatedResponse createAdmin(long actorMemberId, String email, String rawPassword, String name, MemberRole role);

    /**
     * 운영자 계정 목록 (#236). 이메일 · 이름은 마스킹한 값이다.
     *
     * @param active true 면 활성만, false 면 비활성만, null 이면 전체
     */
    PageResponse<AdminAccountItem> searchAdmins(long actorMemberId, Boolean active, int page, int size);

    /** 본문 없는 이전 비활성화 API (#193). 본인도 끌 수 있고, 그때는 지금 토큰도 막는다. */
    void deactivateAdmin(long actorMemberId, long targetMemberId, String currentTokenId);

    /** 역할 변경 (#236). 같은 역할이어도 옛 access 토큰을 끊는다. */
    void changeRole(long actorMemberId, long targetMemberId, MemberRole role);

    /** 상태 변경 (#236). false 면 끄고 옛 access 토큰을 끊는다(같은 상태여도). true 면 다시 켜고 컷오프는 없다. */
    void changeStatus(long actorMemberId, long targetMemberId, boolean active);

    void changePassword(long actorMemberId, String currentTokenId, String currentPassword, String newPassword);
}
