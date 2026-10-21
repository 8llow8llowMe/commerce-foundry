package com.commercefoundry.domainlayer.member.application.command;

/**
 * 관리자 로그인 입력 (#6). 이메일 정규화는 도메인({@code Member.normalizeEmail})이 한다.
 *
 * @param rawPassword 비밀번호 원문. 비교한 뒤에는 쓰지 않는다 — {@link #toString()} 에 싣지 않는다
 */
public record AdminLoginCommand(String email, String rawPassword) {

    /** 이메일 · 비밀번호 원문을 싣지 않는다 — 로그 · 예외 메시지로 새지 않게 ({@code coding-conventions.md} §8). */
    @Override
    public String toString() {
        return "AdminLoginCommand[]";
    }
}
