package com.commercefoundry.domainlayer.member.application.port.out.query;

import com.commercefoundry.security.principal.MemberRole;
import java.time.Instant;

/**
 * 어드민 회원 목록의 한 행 (#6). 도메인 {@code Member} 에 없는 가입 시각을 함께 싣는다. 비밀번호 해시 · 버전은 싣지 않는다.
 *
 * @param phone 숫자만. 없으면 null
 */
public record MemberListQueryResult(long memberId, String email, String name, String phone, MemberRole role, Instant createdAt) {

    /** 개인정보(이메일 · 이름 · 전화)를 싣지 않는다 — 로그 · 예외 메시지로 새지 않게. */
    @Override
    public String toString() {
        return "MemberListQueryResult[memberId=" + memberId + ", role=" + role + "]";
    }
}
