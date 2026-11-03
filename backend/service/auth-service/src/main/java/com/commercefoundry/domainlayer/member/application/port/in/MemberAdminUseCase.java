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
     * @param adminMemberId    조회하는 관리자 — access 토큰의 memberId
     * @param includeOperators 운영자 계정 행까지 볼 수 있는가 — 직원 조회 권한({@code ADMIN_STAFF_READ})이 있을 때만 true (#236).
     *                         false 면 고객 행만 내리고, 운영자 역할로 거르면 빈 페이지다
     * @param role             null 이면 전체(볼 수 있는 범위 안에서)
     * @param page             0 부터
     */
    PageResponse<AdminMemberItem> searchMembers(long adminMemberId, boolean includeOperators, MemberRole role, int page, int size);
}
