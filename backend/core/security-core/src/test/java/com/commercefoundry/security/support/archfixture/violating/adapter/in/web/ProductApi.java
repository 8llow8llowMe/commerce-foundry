package com.commercefoundry.security.support.archfixture.violating.adapter.in.web;

import org.springframework.web.bind.annotation.GetMapping;

/** springdoc {@code XxxApi} 패턴 — 매핑을 인터페이스에 두고 컨트롤러가 구현한다. */
public interface ProductApi {

    @GetMapping("/api/v1/inherited-products")
    String list();
}
