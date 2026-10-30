package com.commercefoundry.domainlayer.member.application.command;

/**
 * 관리자 로그인 2단계 입력 (#238 PR-B).
 *
 * @param challengeToken 1단계가 내준 챌린지 원문. {@link #toString()} 에 싣지 않는다
 * @param code           인증 앱의 6자리 코드 또는 복구 코드. {@link #toString()} 에 싣지 않는다
 */
public record AdminOtpLoginCommand(String challengeToken, String code) {

    /** 챌린지 · 코드 원문을 싣지 않는다 — 로그 · 예외 메시지로 새지 않게 ({@code coding-conventions.md} §8). */
    @Override
    public String toString() {
        return "AdminOtpLoginCommand[]";
    }
}
