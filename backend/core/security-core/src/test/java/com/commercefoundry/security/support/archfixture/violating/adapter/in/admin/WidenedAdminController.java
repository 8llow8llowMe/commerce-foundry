package com.commercefoundry.security.support.archfixture.violating.adapter.in.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 위반 예시 — 운영자 권한이 필요조건이 아닌 어드민 식. 정의되지 않은 기능 권한도 실패다. {@code @Controller} 도 대상이다. {@link #missing()} 은 R1 · R2 둘 다 위반이다. */
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

    @PreAuthorize("hasAuthority('ADMIN_ORDER_READ') or isAuthenticated()")
    @GetMapping("/widened")
    public String widened() {
        return "widened";
    }

    @PreAuthorize("hasAuthority('ADMIN_ORDER_READ') and isAuthenticated() or permitAll()")
    @GetMapping("/precedence")
    public String precedence() {
        return "precedence";
    }

    /** #236 이전 표기 — 런타임은 ADMIN 만 통과시키지만(fail-closed) 새 역할에 기능을 열 수 없어 #588 부터 위반이다. */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/legacy")
    public String legacyAdminRole() {
        return "legacyAdminRole";
    }

    @PreAuthorize("hasAuthority('ADMIN_FOO_READ')")
    @GetMapping("/unknown-feature")
    public String unknownFeature() {
        return "unknownFeature";
    }

    @GetMapping("/missing")
    public String missing() {
        return "missing";
    }
}
