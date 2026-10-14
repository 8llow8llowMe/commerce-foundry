package com.commercefoundry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 배송 서비스 — 배송비 정책(국내/해외) · 송장 · 배송 상태 추적 ({@code service-inventory.md} §1).
 */
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {
    "com.commercefoundry.domainlayer",
    "com.commercefoundry.global"
})
public class DeliveryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeliveryServiceApplication.class, args);
    }
}
