package com.commercefoundry.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API 게이트웨이. 외부 요청의 단일 진입점으로, {@code /api/v1/**} 를 서비스별로 라우팅한다 ({@code api-design-guide.md} §1-2).
 *
 * <p>보안 체인({@code GatewaySecurityConfig})이 Bearer 토큰 검증 · 로그아웃 차단 목록 확인 · {@code /api/v1/admin/**} 의 ADMIN 확인을 한다 (#4).
 * 서비스도 같은 토큰을 다시 검증한다 — 게이트웨이 판정은 첫 번째 방어다 ({@code security-guide.md} §7).
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
