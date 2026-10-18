package com.commercefoundry.security.support.archfixture.violating.adapter.in.web;

import org.springframework.web.bind.annotation.RestController;

/** 위반 예시 — 인터페이스로 받은 핸들러. 이 클래스의 {@code list()} 에는 매핑이 없어 R1 이 보지 못한다(@PreAuthorize 없이 공개). R7 이 막는다. */
@RestController
public class InheritedWebController implements ProductApi {

    @Override
    public String list() {
        return "inherited";
    }
}
