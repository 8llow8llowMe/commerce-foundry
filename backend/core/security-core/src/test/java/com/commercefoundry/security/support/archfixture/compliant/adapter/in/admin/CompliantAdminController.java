package com.commercefoundry.security.support.archfixture.compliant.adapter.in.admin;

import com.commercefoundry.security.principal.AdminAuthority;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 준수 예시 — 운영자 권한이 필요조건인 식. {@code AdminAuthority} 기능 상수 · {@code OPERATOR} (#236 이전 표기 {@code hasRole('ADMIN')} 은 #588 부터 위반 예시다).
 * {@code and} 로 좁히거나 괄호 안에서만 {@code or} 를 쓰는 것은 통과한다.
 */
@RestController
@RequestMapping("/api/v1/admin/compliant")
public class CompliantAdminController {

    @PreAuthorize(AdminAuthority.ORDER_READ)
    @GetMapping
    public String list() {
        return "list";
    }

    @PreAuthorize("hasAuthority('ADMIN_ORDER_MANAGE') and isFullyAuthenticated()")
    @PutMapping
    public String replace() {
        return "replace";
    }

    @PreAuthorize("hasAuthority('ADMIN_ORDER_MANAGE') and (hasAuthority('SCOPE_a') or hasAuthority('SCOPE_b'))")
    @DeleteMapping
    public String remove() {
        return "remove";
    }

    @PreAuthorize(AdminAuthority.ORDER_MANAGE)
    @PostMapping
    public String manageOrder() {
        return "manageOrder";
    }

    @PreAuthorize(AdminAuthority.OPERATOR)
    @PatchMapping
    public String operatorSelf() {
        return "operatorSelf";
    }

    @AdminOnly
    @GetMapping("/composed")
    public String composed() {
        return "composed";
    }
}
