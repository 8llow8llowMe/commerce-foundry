package com.commercefoundry.domainlayer.member.application.info;

/**
 * 가입한 회원. 로그인은 따로 한다 — 가입 응답으로 토큰을 주지 않는다.
 */
public record MemberSignUpInfo(long memberId) {
}
