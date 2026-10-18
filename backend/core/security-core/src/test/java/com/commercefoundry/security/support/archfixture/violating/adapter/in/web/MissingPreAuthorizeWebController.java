package com.commercefoundry.security.support.archfixture.violating.adapter.in.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 위반 예시 — 클래스의 {@code @PreAuthorize} 는 고객 핸들러의 선언으로 치지 않는다. 메서드 둘 다 위반이다. */
@RestController
@PreAuthorize("permitAll()")
@RequestMapping("/api/v1/violating")
public class MissingPreAuthorizeWebController {

    @GetMapping
    public String classLevelOnly() {
        return "open";
    }

    @PostMapping
    public String forgotten() {
        return "forgotten";
    }
}
