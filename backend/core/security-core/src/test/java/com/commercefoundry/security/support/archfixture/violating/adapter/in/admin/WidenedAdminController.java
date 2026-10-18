package com.commercefoundry.security.support.archfixture.violating.adapter.in.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 위반 예시 — ADMIN 이 필요조건이 아닌 어드민 식. {@code @Controller} 도 대상이다. {@link #missing()} 은 R1 · R2 둘 다 위반이다. */
@Controller
@RequestMapping("/api/v1/admin/violating")
public class WidenedAdminController {

    @PreAuthorize("permitAll()")
    @GetMapping("/open")
    public String open() {
        return "open";
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/member")
    public String member() {
        return "member";
    }

    @PreAuthorize("hasRole('ADMIN') or isAuthenticated()")
    @GetMapping("/widened")
    public String widened() {
        return "widened";
    }

    @PreAuthorize("hasRole('ADMIN') and isAuthenticated() or permitAll()")
    @GetMapping("/precedence")
    public String precedence() {
        return "precedence";
    }

    @GetMapping("/missing")
    public String missing() {
        return "missing";
    }
}
