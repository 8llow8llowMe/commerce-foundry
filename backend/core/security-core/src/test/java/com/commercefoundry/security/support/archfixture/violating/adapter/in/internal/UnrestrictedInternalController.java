package com.commercefoundry.security.support.archfixture.violating.adapter.in.internal;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 위반 예시 — 허용 호출자가 없거나 서비스 권한 밖으로 넓힌 internal 식. */
@RestController
public class UnrestrictedInternalController {

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/internal/v1/violating/any-service")
    public String anyService() {
        return "any";
    }

    @PreAuthorize("hasAuthority('SERVICE_order-service') or permitAll()")
    @GetMapping("/internal/v1/violating/widened")
    public String widened() {
        return "widened";
    }

    @GetMapping("/internal/v1/violating/missing")
    public String missing() {
        return "missing";
    }
}
