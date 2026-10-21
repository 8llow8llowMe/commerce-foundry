package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.application.command.AdminSeedCommand;
import com.commercefoundry.domainlayer.member.application.info.AdminSeedOutcome;

/**
 * 초기 관리자 시드 (#6). 기동 직후 한 번 부른다({@code AdminSeedRunner}). HTTP 진입점이 없다.
 */
public interface AdminSeedUseCase {

    /**
     * 이메일의 관리자가 없으면 만든다. 이미 있으면(관리자 · 고객 모두) 아무것도 바꾸지 않는다 — 몇 번을 불러도 결과가 같다(멱등).
     *
     * @throws IllegalStateException 이메일 · 비밀번호 중 하나만 있다 · 비밀번호가 규칙(12~64자, 영문 · 숫자 포함 공백 없는 ASCII) 밖이다 ·
     *                               이메일 형식 · 이름 길이가 규칙 밖이다. 메시지에 값을 싣지 않는다
     */
    AdminSeedOutcome seed(AdminSeedCommand command);
}
