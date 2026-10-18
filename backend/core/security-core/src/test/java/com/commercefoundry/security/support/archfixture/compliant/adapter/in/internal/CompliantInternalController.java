package com.commercefoundry.security.support.archfixture.compliant.adapter.in.internal;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 준수 예시 — 메서드에 선언하면 메서드의 것, 없으면 클래스의 것이 허용 호출자다. 둘 다 서비스 권한만 나열한 식이다. */
@RestController
@PreAuthorize("hasAnyAuthority('SERVICE_order-service', 'SERVICE_payment-service')")
public class CompliantInternalController {

    @PreAuthorize("hasAuthority('SERVICE_order-service')")
    @PostMapping("/internal/v1/compliant/reservations")
    public String reserve() {
        return "reserved";
    }

    @GetMapping("/internal/v1/compliant")
    public String read() {
        return "read";
    }
}
