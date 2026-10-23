package com.commercefoundry.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.apigateway.properties.GatewayClientIpProperties;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 클라이언트 주체(IP 의 HMAC)를 만드는 규칙과 하류 헤더 필터를 단위로 본다 (#135). 실제 라우팅에서 하류에 닿는 값은 {@link ClientSubjectHeaderTest} 가 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClientSubjectResolverTest {

    private static final String KEY = GatewayIntegrationTest.TEST_CLIENT_IP_HASH_KEY;
    private static final GatewayClientIpProperties ENABLED = new GatewayClientIpProperties(true, List.of(), KEY);
    private static final GatewayClientIpProperties DISABLED = new GatewayClientIpProperties(false, List.of(), null);
    private static final ClientIpHasher HASHER = new ClientIpHasher(ENABLED);

    @Test
    @DisplayName("켜져 있으면 비로그인 버킷 키와 같은 값(bucketOf 뒤 HMAC)이고, 한 요청에서 한 번만 계산한다")
    void resolvesOncePerExchange() {
        AtomicInteger resolveCalls = new AtomicInteger();
        ClientIpResolver countingResolver = new ClientIpResolver(ENABLED) {
            @Override
            public Optional<String> resolve(ServerHttpRequest request) {
                resolveCalls.incrementAndGet();
                return super.resolve(request);
            }
        };
        ClientSubjectResolver subjects = new ClientSubjectResolver(ENABLED, countingResolver, HASHER);
        MockServerWebExchange exchange = exchange("2001:db8:1:2::1");

        assertThat(subjects.resolve(exchange)).contains(HASHER.hash(ClientIpResolver.bucketOf("2001:db8:1:2::1")));
        assertThat(subjects.resolve(exchange)).contains(HASHER.hash(ClientIpResolver.bucketOf("2001:db8:1:2::1")));
        assertThat(resolveCalls).hasValue(1);
    }

    @Test
    @DisplayName("꺼져 있으면 IP 를 해석 · 해시하지 않고 비어 있다 — 꺼진 해시기는 키가 없어 부르면 던진다")
    void emptyWhenDisabled() {
        ClientSubjectResolver subjects = new ClientSubjectResolver(DISABLED, new ClientIpResolver(DISABLED), new ClientIpHasher(DISABLED));

        assertThat(subjects.resolve(exchange("203.0.113.7"))).isEmpty();
    }

    @Test
    @DisplayName("원격 주소를 모르면 비어 있고, 빈 결과도 캐시돼 두 번째 호출은 다시 해석하지 않는다")
    void emptyWhenRemoteAddressUnknownIsCached() {
        AtomicInteger resolveCalls = new AtomicInteger();
        ClientIpResolver countingResolver = new ClientIpResolver(ENABLED) {
            @Override
            public Optional<String> resolve(ServerHttpRequest request) {
                resolveCalls.incrementAndGet();
                return super.resolve(request);
            }
        };
        ClientSubjectResolver subjects = new ClientSubjectResolver(ENABLED, countingResolver, HASHER);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products/1"));

        assertThat(subjects.resolve(exchange)).isEmpty();
        assertThat(subjects.resolve(exchange)).isEmpty();
        assertThat(resolveCalls).hasValue(1);
    }

    @Test
    @DisplayName("enabled 는 스위치를 그대로 알린다 — 요청 제한 필터가 꺼짐과 주소 모름을 가른다")
    void enabledReflectsSwitch() {
        assertThat(new ClientSubjectResolver(ENABLED, new ClientIpResolver(ENABLED), HASHER).enabled()).isTrue();
        assertThat(new ClientSubjectResolver(DISABLED, new ClientIpResolver(DISABLED), new ClientIpHasher(DISABLED)).enabled()).isFalse();
    }

    @Test
    @DisplayName("헤더 필터 — 위조 값을 지우고 한 개를 싣는다. 꺼져 있으면 지우기만 한다")
    void headerFilterReplacesOrRemoves() {
        ClientSubjectHeaderFilter enabled = new ClientSubjectHeaderFilter(new ClientSubjectResolver(ENABLED, new ClientIpResolver(ENABLED), HASHER));
        ClientSubjectHeaderFilter disabled = new ClientSubjectHeaderFilter(
            new ClientSubjectResolver(DISABLED, new ClientIpResolver(DISABLED), new ClientIpHasher(DISABLED)));

        assertThat(forwardedHeaders(enabled, forged("203.0.113.7")).get(ClientSubjectHeaderFilter.HEADER)).containsExactly(HASHER.hash("203.0.113.7"));
        assertThat(forwardedHeaders(disabled, forged("203.0.113.7")).containsHeader(ClientSubjectHeaderFilter.HEADER)).isFalse();
    }

    @Test
    @DisplayName("헤더 필터는 요청 제한 필터 뒤, 라우팅 앞에 돈다")
    void headerFilterRunsAfterRateLimit() {
        ClientSubjectHeaderFilter filter = new ClientSubjectHeaderFilter(new ClientSubjectResolver(DISABLED, new ClientIpResolver(DISABLED), new ClientIpHasher(DISABLED)));

        assertThat(filter.getOrder()).isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 100).isLessThan(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    @DisplayName("켰는데 신뢰 프록시가 없으면 기동 때 WARN 한 줄을 남긴다 — 값 · 키는 싣지 않는다")
    void warnsWhenEnabledWithoutTrustedProxy(CapturedOutput output) {
        new ClientIpResolver(ENABLED);

        assertThat(output.getOut()).contains("WARN").contains("gateway.client-ip.trusted-proxies(GATEWAY_TRUSTED_PROXIES) is empty").doesNotContain(KEY);
    }

    @Test
    @DisplayName("꺼져 있거나 신뢰 프록시가 있으면 WARN 을 남기지 않는다")
    void noWarnWhenDisabledOrProxyConfigured(CapturedOutput output) {
        new ClientIpResolver(DISABLED);
        new ClientIpResolver(new GatewayClientIpProperties(true, List.of("10.0.0.0/8"), KEY));

        assertThat(output.getOut()).doesNotContain("trusted-proxies(GATEWAY_TRUSTED_PROXIES) is empty");
    }

    private static MockServerWebExchange exchange(String remoteIp) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products/1").remoteAddress(new InetSocketAddress(remoteIp, 40000)));
    }

    private static MockServerWebExchange forged(String remoteIp) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products/1")
            .remoteAddress(new InetSocketAddress(remoteIp, 40000))
            .header("x-client-subject", "forged-lower")
            .header("X-CLIENT-SUBJECT", "forged-upper"));
    }

    /** 체인으로 넘어간 요청의 헤더. */
    private static HttpHeaders forwardedHeaders(ClientSubjectHeaderFilter filter, MockServerWebExchange exchange) {
        AtomicReference<HttpHeaders> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = next -> {
            forwarded.set(next.getRequest().getHeaders());
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return forwarded.get();
    }
}
