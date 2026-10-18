package com.commercefoundry.security.support.archfixture.violating.presentation;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 위반 예시 — adapter.in 밖(presentation)의 {@code @Controller}. */
@Controller
public class PresentationController {

    @GetMapping("/api/v1/presentation")
    public String page() {
        return "page";
    }
}
