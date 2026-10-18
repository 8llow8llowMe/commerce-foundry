package com.commercefoundry.security.support.archfixture.compliant.adapter.in.webhook;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 준수 예시 — PG 웹훅 수신 컨트롤러는 adapter.in.webhook 에 둘 수 있다(R0). 서명 · 재조회로 검증하므로 정적 규칙 R1~R3 의 대상이 아니다.
 * {@code /api/} 경로라 런타임 가드는 {@code @PreAuthorize} 를 요구한다 — 공개 수신이면 {@code permitAll()} 을 적는다.
 */
@RestController
public class CompliantWebhookController {

    @PreAuthorize("permitAll()")
    @PostMapping("/api/v1/webhooks/pg")
    public String receive() {
        return "received";
    }
}
