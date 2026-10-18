package com.commercefoundry.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.apigateway.properties.GatewayClientIpProperties;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClientIpHasherTest {

    private final ClientIpHasher hasher = new ClientIpHasher(new GatewayClientIpProperties(List.of(), GatewayIntegrationTest.TEST_CLIENT_IP_HASH_KEY));

    @Test
    @DisplayName("IP 원문이 없는 22자 base64url 값이다")
    void hashHasNoRawIp() {
        String hash = hasher.hash("203.0.113.7");

        assertThat(hash).hasSize(22).matches("[A-Za-z0-9_-]+").doesNotContain("203").doesNotContain("113");
    }

    @Test
    @DisplayName("같은 IP · 같은 키면 같은 값이다 — 게이트웨이 인스턴스끼리 같은 버킷에 모인다")
    void sameIpSameHash() {
        ClientIpHasher other = new ClientIpHasher(new GatewayClientIpProperties(List.of(), GatewayIntegrationTest.TEST_CLIENT_IP_HASH_KEY));

        assertThat(hasher.hash("203.0.113.7")).isEqualTo(other.hash("203.0.113.7"));
        assertThat(hasher.hash("203.0.113.7")).isNotEqualTo(hasher.hash("203.0.113.8"));
    }

    @Test
    @DisplayName("키가 다르면 같은 IP 도 다른 값이다 — 키 없이 전수 대입으로 되돌릴 수 없다")
    void differentKeyDifferentHash() {
        String otherKey = Base64.getEncoder().encodeToString("another-test-only-client-ip-hash-key".getBytes());
        ClientIpHasher other = new ClientIpHasher(new GatewayClientIpProperties(List.of(), otherKey));

        assertThat(hasher.hash("203.0.113.7")).isNotEqualTo(other.hash("203.0.113.7"));
    }
}
