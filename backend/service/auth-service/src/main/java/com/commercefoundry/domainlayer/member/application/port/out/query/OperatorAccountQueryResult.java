package com.commercefoundry.domainlayer.member.application.port.out.query;

import com.commercefoundry.security.principal.MemberRole;
import java.time.Instant;

/**
 * 운영자 계정 목록의 한 행 (#236). 도메인 {@code Member} 에 없는 생성 시각을 함께 싣는다. 비밀번호 해시 · 버전 · 전화는 싣지 않는다.
 *
 * @param deactivatedAt 비활성화 시각. 활성 운영자는 null
 */
public record OperatorAccountQueryResult(long memberId, String email, String name, MemberRole role, Instant deactivatedAt, Instant createdAt) {

    /** 개인정보(이메일 · 이름)를 싣지 않는다 — 로그 · 예외 메시지로 새지 않게. */
    @Override
    public String toString() {
        return "OperatorAccountQueryResult[memberId=" + memberId + ", role=" + role + ", deactivatedAt=" + deactivatedAt + "]";
    }
}
