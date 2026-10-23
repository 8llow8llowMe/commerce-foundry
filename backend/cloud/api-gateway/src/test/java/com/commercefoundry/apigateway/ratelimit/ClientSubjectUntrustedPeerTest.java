package com.commercefoundry.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 계수를 켰지만 신뢰 프록시가 없을 때(테스트 클라이언트 {@code 127.0.0.1} 은 신뢰하지 않는 피어)의 {@code X-Client-Subject} 를 실제 Netty 로 본다 (#135).
 * 피어가 보낸 {@code X-Forwarded-For} 는 무시하고 소켓 피어 주소의 HMAC 을 싣는다.
 */
@GatewayIntegrationTest
@TestPropertySource(properties = {GatewayIntegrationTest.CLIENT_IP_ENABLED, GatewayIntegrationTest.CLIENT_IP_HASH_KEY})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ClientSubjectUntrustedPeerTest {

    private static final UpstreamHeaderRecorder UPSTREAM = new UpstreamHeaderRecorder();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ClientIpHasher clientIpHasher;

    @DynamicPropertySource
    static void registerServiceInstances(DynamicPropertyRegistry registry) {
        UPSTREAM.register(registry);
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop();
    }

    @BeforeEach
    void reset() {
        UPSTREAM.clear();
    }

    @Test
    @DisplayName("신뢰하지 않는 피어의 X-Forwarded-For 는 무시한다 — 값은 피어 주소의 HMAC 이고, 위조 X-Client-Subject 는 지운다")
    void untrustedPeerForwardedForIsIgnored() {
        webTestClient.get().uri(ClientSubjectHeaderTest.PRODUCT)
            .header("X-Forwarded-For", "198.51.100.7")
            .header(ClientSubjectHeaderFilter.HEADER, "forged-1", "forged-2")
            .exchange()
            .expectStatus().isOk();

        List<String> values = UPSTREAM.onlyRequestHeader(ClientSubjectHeaderFilter.HEADER);
        assertThat(values).containsExactly(clientIpHasher.hash("127.0.0.1"));
        assertThat(values.getFirst()).matches(ClientSubjectHeaderTest.SUBJECT_FORMAT).isNotEqualTo(clientIpHasher.hash("198.51.100.7"));
    }

    @Test
    @DisplayName("하류에는 X-Forwarded-* · Forwarded 가 없다 — 클라이언트가 보낸 값도, 게이트웨이 소켓 피어도 실리지 않는다")
    void downstreamHasNoForwardingHeaders() {
        webTestClient.get().uri(ClientSubjectHeaderTest.PRODUCT).headers(UpstreamHeaderRecorder::spoofForwarding).exchange().expectStatus().isOk();

        assertThat(UPSTREAM.onlyRequestHeaderNames()).noneMatch(UpstreamHeaderRecorder::isForwardingHeader);
    }
}
