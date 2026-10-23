package com.commercefoundry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 운영 서비스 — 백그라운드 작업(등록 · 실행 · 재시도 · 진행률 · 결과)과 어드민 알림 ({@code service-inventory.md} §1).
 */
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {
    "com.commercefoundry.domainlayer",
    "com.commercefoundry.global"
})
public class OperationsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OperationsServiceApplication.class, args);
    }
}
