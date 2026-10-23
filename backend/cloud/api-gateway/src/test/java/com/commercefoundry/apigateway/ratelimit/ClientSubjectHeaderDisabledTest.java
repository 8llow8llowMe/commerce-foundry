package com.commercefoundry.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 끈 기본값({@code gateway.client-ip.enabled=false})에서 {@code X-Client-Subject} 를 싣지 않고, 클라이언트가 보낸 같은 이름 헤더는 그래도 지우는지 실제 Netty 로 본다 (#135).
 */
@GatewayIntegrationTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ClientSubjectHeaderDisabledTest {

    private static final UpstreamHeaderRecorder UPSTREAM = new UpstreamHeaderRecorder();

    @Autowired
    private WebTestClient webTestClient;

    @LocalServerPort
    private int port;

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
    @DisplayName("꺼져 있으면 헤더를 싣지 않는다 — 하류는 IP 차원을 건너뛴다")
    void noSubjectWhenDisabled() {
        webTestClient.get().uri(ClientSubjectHeaderTest.PRODUCT).exchange().expectStatus().isOk();

        assertThat(UPSTREAM.onlyRequestHeader(ClientSubjectHeaderFilter.HEADER)).isEmpty();
    }

    @Test
    @DisplayName("꺼져 있어도 클라이언트가 보낸 X-Client-Subject 는 대소문자 · 개수와 무관하게 전부 지운다")
    void forgedSubjectHeadersAreRemovedWhenDisabled() {
        String status = UpstreamHeaderRecorder.rawGet(port, ClientSubjectHeaderTest.PRODUCT, List.of(
            "x-client-subject: forged-lower", "X-CLIENT-SUBJECT: forged-upper", "X-Client-Subject: forged-1", "X-Client-Subject: forged-2"));

        assertThat(status).contains(" 200 ");
        assertThat(UPSTREAM.onlyRequestHeader(ClientSubjectHeaderFilter.HEADER)).isEmpty();
    }

    @Test
    @DisplayName("기본 설정에서 하류에는 X-Forwarded-* · Forwarded 가 없다 — 클라이언트가 보낸 값도, 게이트웨이 소켓 피어도 실리지 않는다")
    void downstreamHasNoForwardingHeaders() {
        webTestClient.get().uri(ClientSubjectHeaderTest.PRODUCT).headers(UpstreamHeaderRecorder::spoofForwarding).exchange().expectStatus().isOk();

        assertThat(UPSTREAM.onlyRequestHeaderNames()).noneMatch(UpstreamHeaderRecorder::isForwardingHeader);
    }
}
