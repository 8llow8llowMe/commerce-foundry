package com.commercefoundry.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API 게이트웨이. 외부 요청의 단일 진입점으로, {@code /api/v1/**} 를 서비스별로 라우팅한다 ({@code api-design-guide.md} §1-2).
 *
 * <p>JWT 검증·{@code /api/v1/admin/**} 의 ADMIN 확인은 #4 · #6 에서 붙인다.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
