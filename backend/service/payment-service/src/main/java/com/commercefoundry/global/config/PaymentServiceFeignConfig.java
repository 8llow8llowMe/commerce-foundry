package com.commercefoundry.global.config;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 클라이언트 스캔 ({@code bootstrap-conventions.md} Application). 지금은 외부 API(토스페이먼츠) 클라이언트 하나다.
 *
 * <ul>
 *   <li>Application 클래스나 {@code *FeaturesConfig} 에 두지 않는다 — 슬라이스 테스트({@code @DataJpaTest} 등)는 Application 클래스를 설정으로 쓰고
 *       리포지토리 테스트는 {@code *FeaturesConfig}(JPA Auditing)를 import 하는데, 거기 있으면 Feign 자동설정이 없는 슬라이스에서 클라이언트를 만들려다 실패한다.</li>
 *   <li>{@code defaultConfiguration} 을 두지 않는다(ArchUnit R6) — 설정은 클라이언트마다 {@code configuration} 으로 붙인다. 전역으로 두면 토스 요청에
 *       서비스 토큰이 실리거나, 앞으로 붙을 internal 클라이언트에 토스 설정이 실린다 ({@code security-guide.md} §9-5).</li>
 * </ul>
 */
@Configuration
@EnableFeignClients(basePackages = "com.commercefoundry.domainlayer")
public class PaymentServiceFeignConfig {

}
