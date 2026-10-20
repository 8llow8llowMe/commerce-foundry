package com.commercefoundry.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.apigateway.properties.GatewayClientIpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code application.yml} 기본값에서 비로그인 IP 계수가 꺼져 있는지 본다 ({@code security-guide.md} §7-3).
 * 공통 통합 테스트 설정은 HMAC 키를 넣지 않으므로 이 컨텍스트가 뜬 것 자체가 "끈 기본값에서는 키 없이 뜬다" 의 증거다.
 * 다른 통합 테스트와 컨텍스트를 공유한다.
 */
@GatewayIntegrationTest
class ClientIpDefaultDisabledTest {

    @Autowired
    private GatewayClientIpProperties properties;

    @Autowired
    private ClientIpHasher clientIpHasher;

    @Test
    @DisplayName("기본 설정에서는 비로그인 IP 계수가 꺼져 있고 키 없이 뜬다 — BFF 뒤에서 비로그인 요청 전체가 한 버킷이 되지 않게 (#134 · #135)")
    void clientIpCountingIsOffByDefault() {
        assertThat(properties.enabled()).isFalse();
        assertThatThrownBy(() -> clientIpHasher.hash("203.0.113.7"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("203.0.113.7");
    }
}
