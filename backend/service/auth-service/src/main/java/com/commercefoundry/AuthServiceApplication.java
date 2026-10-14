package com.commercefoundry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 인증 서비스 — 회원가입·로그인 · JWT 발급·재발급·로그아웃 · 카카오·네이버 소셜 · 관리자 계정 · 배송지 주소록 ({@code service-inventory.md} §1).
 */
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {
    "com.commercefoundry.domainlayer",
    "com.commercefoundry.global"
})
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
