package com.commercefoundry.security.support.archfixture.violating.adapter.in.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

/** 매핑 메서드를 물려주는 상위 클래스. 컨트롤러가 아니라 R1 · R2 의 대상이 아니다. */
public abstract class AbstractAdminController {

    @PreAuthorize("permitAll()")
    @GetMapping("/api/v1/admin/inherited")
    public String inherited() {
        return "inherited";
    }
}
