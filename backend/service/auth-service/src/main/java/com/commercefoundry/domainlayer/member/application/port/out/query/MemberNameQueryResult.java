package com.commercefoundry.domainlayer.member.application.port.out.query;

/**
 * 회원 아이디와 이름. 표시 이름을 붙일 때 한 페이지를 한 번에 읽는다 (#322). 비밀번호 · 연락처는 싣지 않는다.
 *
 * @param name 저장된 이름. 없으면 null — 호출한 쪽이 마스킹하지 않고 null 로 둔다
 */
public record MemberNameQueryResult(long memberId, String name) {

    /** 이름 원문을 싣지 않는다 — 로그 · 예외 메시지로 새지 않게. */
    @Override
    public String toString() {
        return "MemberNameQueryResult[memberId=" + memberId + "]";
    }
}
