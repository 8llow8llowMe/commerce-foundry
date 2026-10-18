package com.commercefoundry.security.support.archfixture.compliant.adapter.in.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 준수 예시 — 고객 핸들러는 식이 무엇이든 메서드마다 {@code @PreAuthorize} 가 있으면 된다. 매핑이 없는 메서드는 핸들러가 아니다. */
@RestController
@RequestMapping("/api/v1/compliant")
public class CompliantWebController {

    @PreAuthorize("permitAll()")
    @GetMapping
    public String open() {
        return "open";
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public String member() {
        return "member";
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @RequestMapping(path = "/legacy")
    public String legacy() {
        return "legacy";
    }

    public String notAHandler() {
        return "helper";
    }
}
