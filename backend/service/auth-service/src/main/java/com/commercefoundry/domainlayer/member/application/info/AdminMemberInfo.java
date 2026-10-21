package com.commercefoundry.domainlayer.member.application.info;

import com.commercefoundry.security.principal.MemberRole;
import java.time.Instant;

/**
 * 어드민 회원 목록의 한 회원 (#6). 개인정보는 원문 그대로다 — 마스킹은 응답을 만드는 Presenter({@code MemberAdminPresenter})가 한다.
 *
 * @param phone 숫자만. 없으면 null
 */
public record AdminMemberInfo(long memberId, String email, String name, String phone, MemberRole role, Instant createdAt) {

    /** 개인정보(이메일 · 이름 · 전화)를 싣지 않는다 — 로그 · 예외 메시지로 새지 않게. */
    @Override
    public String toString() {
        return "AdminMemberInfo[memberId=" + memberId + ", role=" + role + "]";
    }
}
