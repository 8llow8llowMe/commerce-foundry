package com.commercefoundry.security.support.archfixture.compliant.adapter.in.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 준수 예시 — ADMIN 이 필요조건인 식. {@code and} 로 좁히거나 괄호 안에서만 {@code or} 를 쓰는 것은 통과한다. */
@RestController
@RequestMapping("/api/v1/admin/compliant")
public class CompliantAdminController {

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public String list() {
        return "list";
    }

    @PreAuthorize("hasRole('ADMIN') and isFullyAuthenticated()")
    @PutMapping
    public String replace() {
        return "replace";
    }

    @PreAuthorize("hasRole('ADMIN') and (hasAuthority('SCOPE_a') or hasAuthority('SCOPE_b'))")
    @DeleteMapping
    public String remove() {
        return "remove";
    }

    @AdminOnly
    @GetMapping("/composed")
    public String composed() {
        return "composed";
    }
}
