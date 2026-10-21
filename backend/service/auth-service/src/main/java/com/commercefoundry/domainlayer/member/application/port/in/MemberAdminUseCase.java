package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminMemberItem;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.principal.MemberRole;

/**
 * 어드민 회원 관리 (#6). 지금은 목록 조회만 있다.
 */
public interface MemberAdminUseCase {

    /**
     * 최신순(아이디 내림차순) 회원 목록. 개인정보는 마스킹해 내린다. 조회 행위를 감사 로그에 남긴다.
     *
     * @param adminMemberId 조회하는 관리자 — access 토큰의 memberId
     * @param role          null 이면 전체
     * @param page          0 부터
     */
    PageResponse<AdminMemberItem> searchMembers(long adminMemberId, MemberRole role, int page, int size);
}
