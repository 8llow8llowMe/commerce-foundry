package com.commercefoundry.domainlayer.member.application.command;

/**
 * 초기 관리자 시드 입력 (#6). 설정값({@code auth.admin-seed.*})을 그대로 옮긴다 — 빈 값 · null 판정과 규칙 검사는 {@code AdminSeedProcessor#validate} 가 한다(정규화한 같은 타입을 돌려준다).
 *
 * @param rawPassword 비밀번호 원문. 해시한 뒤에는 쓰지 않는다 — {@link #toString()} 에 싣지 않는다
 */
public record AdminSeedCommand(String email, String rawPassword, String name) {

    /** 이메일 · 비밀번호 · 이름 원문을 싣지 않는다 — 로그 · 예외 메시지로 새지 않게. */
    @Override
    public String toString() {
        return "AdminSeedCommand[]";
    }
}
