package com.commercefoundry.apigateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.apigateway.GatewayIntegrationTest;
import com.commercefoundry.apigateway.properties.GatewayClientIpProperties;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class ClientIpResolverTest {

    private static final String KEY = GatewayIntegrationTest.TEST_CLIENT_IP_HASH_KEY;

    @Nested
    class WithoutTrustedProxies {

        private final ClientIpResolver resolver = new ClientIpResolver(new GatewayClientIpProperties(List.of(), KEY));

        @ParameterizedTest
        @ValueSource(strings = {"198.51.100.1", "198.51.100.1, 10.0.0.1", "unknown", ""})
        @DisplayName("신뢰 프록시가 없으면(기본) X-Forwarded-For 가 무엇이든 소켓의 원격 주소다 — 클라이언트가 버킷을 고를 수 없다")
        void ignoresForwardedFor(String forwardedFor) {
            assertThat(resolver.resolve(request("203.0.113.7", forwardedFor))).contains("203.0.113.7");
        }

        @Test
        @DisplayName("원격 주소를 모르면 비어 있다")
        void emptyWithoutRemoteAddress() {
            assertThat(resolver.resolve(MockServerHttpRequest.post("/api/v1/carts/me/items").build())).isEmpty();
        }
    }

    @Nested
    class WithTrustedProxies {

        private final ClientIpResolver resolver = new ClientIpResolver(new GatewayClientIpProperties(List.of("10.0.0.0/8", "2001:db8::/32"), KEY));

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            // X-Forwarded-For                                  | 클라이언트
            "198.51.100.1                                         | 198.51.100.1",
            // 클라이언트가 맨 앞에 가짜 값을 넣어도 LB 가 덧붙인 실제 주소(오른쪽)를 쓴다
            "1.2.3.4, 198.51.100.1                                | 198.51.100.1",
            // 신뢰 프록시를 여러 단 거쳤다
            "198.51.100.1, 10.1.1.1, 10.2.2.2                     | 198.51.100.1",
            "2001:db8::1, 2001:0db8:0000:0000:0000:0000:0000:0001 | 10.0.0.5",
            "198.51.100.9, 2001:db8::7                            | 198.51.100.9"
        })
        @DisplayName("원격 주소가 신뢰 프록시면 X-Forwarded-For 를 오른쪽부터 읽어 신뢰 프록시가 아닌 첫 주소를 쓴다")
        void readsRightmostUntrustedHop(String forwardedFor, String expected) {
            assertThat(resolver.resolve(request("10.0.0.5", forwardedFor))).contains(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"evil.example.com", "198.51.100.1:443", "[2001:db8::1]", "1.2.3.256", "not-an-ip, 198.51.100.1, garbage", ".a:b:c", ".::", "01.2.3.4"})
        @DisplayName("오른쪽부터 읽다 주소가 아닌 값을 만나면 프록시 주소를 쓴다 — 이름을 찾지 않고 닫힌 쪽으로 간다")
        void fallsBackToProxyOnMalformedHop(String forwardedFor) {
            assertThat(resolver.resolve(request("10.0.0.5", forwardedFor))).contains("10.0.0.5");
        }

        @Test
        @DisplayName("헤더가 없거나 전부 신뢰 프록시면 프록시 주소를 쓴다")
        void fallsBackToProxyWithoutClientHop() {
            assertThat(resolver.resolve(request("10.0.0.5", null))).contains("10.0.0.5");
            assertThat(resolver.resolve(request("10.0.0.5", "10.9.9.9, 10.8.8.8"))).contains("10.0.0.5");
        }

        @Test
        @DisplayName("여러 줄로 온 X-Forwarded-For 는 순서대로 이어서 읽는다")
        void joinsMultipleHeaderLines() {
            ServerHttpRequest request = MockServerHttpRequest.post("/api/v1/carts/me/items")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 40000))
                .header(ClientIpResolver.X_FORWARDED_FOR, "1.2.3.4", "198.51.100.1, 10.1.1.1")
                .build();

            assertThat(resolver.resolve(request)).contains("198.51.100.1");
        }

        @Test
        @DisplayName("IPv4-mapped IPv6(::ffff:a.b.c.d)는 IPv4 로 보고 IPv4 신뢰 대역과 대조한다")
        void ipv4MappedAddressIsTreatedAsIpv4() {
            assertThat(resolver.resolve(request("10.0.0.5", "198.51.100.1, ::ffff:10.1.1.1"))).contains("198.51.100.1");
            assertThat(resolver.resolve(request("10.0.0.5", "::ffff:198.51.100.1"))).contains("198.51.100.1");
        }

        @Test
        @DisplayName("원격 주소가 신뢰 프록시가 아니면 헤더를 보지 않는다")
        void untrustedRemoteIgnoresHeader() {
            assertThat(resolver.resolve(request("203.0.113.7", "198.51.100.1"))).contains("203.0.113.7");
        }
    }

    @Nested
    class Buckets {

        @Test
        @DisplayName("IPv6 는 /64 로 묶는다 — 한 가입자가 /64 안에서 주소를 바꿔 가며 새 버킷을 얻지 못하게")
        void ipv6IsGroupedBySlash64() {
            assertThat(ClientIpResolver.bucketOf("2001:db8:1:2:aaaa::1")).isEqualTo(ClientIpResolver.bucketOf("2001:db8:1:2:bbbb::2"))
                .isEqualTo("2001:db8:1:2:0:0:0:0/64");
            assertThat(ClientIpResolver.bucketOf("2001:db8:1:2::1")).isNotEqualTo(ClientIpResolver.bucketOf("2001:db8:1:3::1"));
        }

        @Test
        @DisplayName("IPv4 는 주소 하나 단위다")
        void ipv4IsSingleAddress() {
            assertThat(ClientIpResolver.bucketOf("203.0.113.7")).isEqualTo("203.0.113.7");
        }
    }

    @Nested
    class Properties {

        @ParameterizedTest
        @ValueSource(strings = {"10.0.0.0/33", "not-a-cidr", "10.0.0.0/8/1", "0.0.0.0/0", "10.0.0.0/7", "::/0", "2001:db8::/31"})
        @DisplayName("신뢰 프록시 값이 주소 · CIDR 이 아니거나 너무 넓으면(IPv4 /8 · IPv6 /32 미만) 기동하지 않는다")
        void rejectsInvalidTrustedProxy(String proxy) {
            assertThatThrownBy(() -> new GatewayClientIpProperties(List.of(proxy), KEY)).isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "${GATEWAY_CLIENT_IP_HASH_KEY}", "not base64!!"})
        @DisplayName("HMAC 키가 비었거나 풀리지 않았거나 base64 가 아니면 기동하지 않는다")
        void rejectsMissingKey(String key) {
            assertThatThrownBy(() -> new GatewayClientIpProperties(List.of(), key)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("HMAC 키가 32바이트 미만이면 기동하지 않고, 오류 메시지에 키 값을 싣지 않는다")
        void rejectsShortKey() {
            String shortKey = Base64.getEncoder().encodeToString(new byte[31]);

            assertThatThrownBy(() -> new GatewayClientIpProperties(List.of(), shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(shortKey);
        }

        @Test
        @DisplayName("toString 에 HMAC 키를 싣지 않는다")
        void toStringMasksKey() {
            assertThat(new GatewayClientIpProperties(List.of(), KEY).toString()).doesNotContain(KEY).contains("hashKey=****");
        }

        @Test
        @DisplayName("빈 항목 · 공백은 버린다 — 환경변수가 비면 신뢰 프록시 없음이다")
        void stripsBlankProxies() {
            assertThat(new GatewayClientIpProperties(List.of("", " 10.0.0.0/8 "), KEY).trustedProxies()).containsExactly("10.0.0.0/8");
            assertThat(new GatewayClientIpProperties(null, KEY).trustedProxies()).isEmpty();
        }
    }

    private static ServerHttpRequest request(String remoteIp, String forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post("/api/v1/carts/me/items")
            .remoteAddress(new InetSocketAddress(remoteIp, 40000));
        if (forwardedFor != null) {
            builder.header(ClientIpResolver.X_FORWARDED_FOR, forwardedFor);
        }
        return builder.build();
    }
}
