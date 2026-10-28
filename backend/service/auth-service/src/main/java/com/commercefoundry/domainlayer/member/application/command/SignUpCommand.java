package com.commercefoundry.domainlayer.member.application.command;

import com.commercefoundry.domainlayer.member.domain.model.SignUpAgreements;

/**
 * 회원가입 입력. 요청 검증을 지나온 값이고 정규화는 도메인({@code Member})이 한다.
 *
 * @param rawPassword 비밀번호 원문. 해시한 뒤에는 쓰지 않는다 — {@link #toString()} 에 싣지 않는다
 * @param phone 없으면 null
 */
public record SignUpCommand(String email, String rawPassword, String name, String phone, SignUpAgreements agreements, String clientSubject) {

    public SignUpCommand(String email, String rawPassword, String name, String phone, SignUpAgreements agreements) {
        this(email, rawPassword, name, phone, agreements, null);
    }

    /** 게이트웨이의 불투명 IP 식별자. 인증 근거로 쓰지 않고 로그에도 싣지 않는다. */
    public SignUpCommand withClientSubject(String subject) {
        return new SignUpCommand(email, rawPassword, name, phone, agreements, subject);
    }

    /** 비밀번호 원문 · 개인정보를 싣지 않는다 — 로그 · 예외 메시지로 새지 않게 ({@code coding-conventions.md} §8). */
    @Override
    public String toString() {
        return "SignUpCommand[agreements=" + agreements + "]";
    }
}
