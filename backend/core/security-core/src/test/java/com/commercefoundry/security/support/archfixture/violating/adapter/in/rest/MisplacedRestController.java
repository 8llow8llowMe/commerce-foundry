package com.commercefoundry.security.support.archfixture.violating.adapter.in.rest;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 위반 예시 — 변형 패키지(adapter.in.rest)의 어드민 핸들러. R1~R3 이 보지 않아 permitAll() 이 그대로 통과하므로 R0 이 막는다. */
@RestController
public class MisplacedRestController {

    @PreAuthorize("permitAll()")
    @GetMapping("/api/v1/admin/misplaced")
    public String misplaced() {
        return "misplaced";
    }
}
