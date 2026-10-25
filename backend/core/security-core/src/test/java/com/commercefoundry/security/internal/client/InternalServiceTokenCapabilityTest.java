package com.commercefoundry.security.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.internal.InternalTokenIssuer;
import com.commercefoundry.security.support.TestServiceTokens;
import feign.Capability;
import feign.Client;
import feign.Feign;
import feign.Param;
import feign.Request;
import feign.RequestInterceptor;
import feign.RequestLine;
import feign.RequestTemplate;
import feign.Response;
import feign.Target;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.commercefoundry.common.log.CorrelationIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * internal Feign 요청의 <b>최종</b> {@code Authorization} 이 서비스 토큰 하나로 맞춰지는지 본다. 왕복 테스트는 실제 Feign 파이프라인
 * ({@code Feign.builder()} → 인터셉터 → 대상 URL → Capability 로 감싼 Client)을 거치고, 요청을 붙잡는 Client 가 보낸 그대로를 본다.
 */
public class InternalServiceTokenCapabilityTest {

    private static final String CALLER = TestServiceTokens.CALLER;
    private static final String TARGET = TestServiceTokens.SERVICE_ID;

    private final InternalTokenIssuer issuer = TestServiceTokens.issuer(CALLER, Instant.now());
    private final InternalServiceTokenCapability capability = new InternalServiceTokenCapability(issuer);

    // ── 실제 Feign 파이프라인 왕복 ─────────────────────────────────

    @Test
    @DisplayName("앞 · 뒤 인터셉터가 넣은 Authorization(사용자 토큰 · 외부 키)을 모두 지우고 서비스 토큰 하나만 보낸다 — aud=대상 이름, iss=이 서비스")
    void finalRequestCarriesOnlyServiceToken() {
        CapturingClient sent = new CapturingClient();
        ProductStockClient client = Feign.builder()
            .client(sent)
            .requestInterceptor(template -> template.header("Authorization", "Bearer user-access-token"))
            .addCapability(capability)
            // 우리 설정보다 뒤에 붙는 인터셉터 — 부모 컨텍스트의 전역 인터셉터 · default 설정이 이 자리다
            .requestInterceptor(template -> template.header("authorization", "Basic pg-secret-key"))
            .requestInterceptor(template -> template.header("X-Request-Id", "req-1"))
            .target(new Target.HardCodedTarget<>(ProductStockClient.class, TARGET, "http://" + TARGET));

        assertThat(client.stocks("1,2")).isEqualTo("ok");

        Request request = sent.requests.getFirst();
        List<String> authorization = authorizationValues(request);
        assertThat(authorization).singleElement().asString().startsWith("Bearer ").doesNotContain("user-access-token", "pg-secret-key");
        Jwt jwt = decodeAs(TARGET, authorization.getFirst().substring("Bearer ".length()));
        assertThat(jwt.getAudience()).containsExactly(TARGET);
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(CALLER);
        // 나머지는 그대로 간다
        assertThat(request.url()).startsWith("http://product-service/internal/v1/stocks?skuIds=1");
        assertThat(request.headers().get("X-Request-Id")).containsExactly("req-1");
    }

    @Test
    @DisplayName("상관 id 는 MDC 의 검증된 값만 싣고, 인터셉터가 넣은 헤더는 지운다 — Authorization 은 여전히 서비스 토큰 하나다")
    void correlationIdComesFromMdcOnly() {
        CapturingClient sent = new CapturingClient();
        ProductStockClient client = Feign.builder()
            .client(sent)
            .requestInterceptor(template -> template.header(CorrelationIds.HEADER, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\nforged"))
            .requestInterceptor(template -> template.header("Authorization", "Bearer user-access-token"))
            .addCapability(capability)
            .target(new Target.HardCodedTarget<>(ProductStockClient.class, TARGET, "http://" + TARGET));

        MDC.put(CorrelationIds.MDC_KEY, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        try {
            assertThat(client.stocks("1")).isEqualTo("ok");
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }

        Request request = sent.requests.getFirst();
        assertThat(request.headers().get(CorrelationIds.HEADER)).containsExactly("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertThat(authorizationValues(request)).singleElement().asString().startsWith("Bearer ").doesNotContain("user-access-token");

        client.stocks("1");
        assertThat(sent.requests.get(1).headers().get(CorrelationIds.HEADER)).isNull();
    }

    @Test
    @DisplayName("다른 Capability 가 Client 단계에서 Authorization 을 더해도 지운다 — 우리 것이 가장 먼저 더해져 실제 Client 를 바로 감싼다")
    void outerCapabilityHeaderIsAlsoRemoved() {
        Capability addsHeaderAtClient = new HeaderAddingCapability();
        List<Capability> ordered = new ArrayList<>(List.of(addsHeaderAtClient, capability));
        ordered.sort(AnnotationAwareOrderComparator.INSTANCE);
        assertThat(ordered.getFirst()).as("Spring Cloud OpenFeign 이 정렬하는 순서 — 우리 것이 먼저").isSameAs(capability);

        CapturingClient sent = new CapturingClient();
        Feign.Builder builder = Feign.builder().client(sent);
        ordered.forEach(builder::addCapability);
        builder.target(new Target.HardCodedTarget<>(ProductStockClient.class, TARGET, "http://" + TARGET)).stocks("1");

        assertThat(authorizationValues(sent.requests.getFirst())).singleElement().asString().doesNotContain("leaked-by-capability");
    }

    @Test
    @DisplayName("요청마다 새 토큰을 싣는다 — 캐시하지 않는다")
    void issuesNewTokenPerRequest() {
        CapturingClient sent = new CapturingClient();
        ProductStockClient client = Feign.builder().client(sent).addCapability(capability)
            .target(new Target.HardCodedTarget<>(ProductStockClient.class, TARGET, "http://" + TARGET));

        client.stocks("1");
        client.stocks("1");

        assertThat(authorizationValues(sent.requests.get(0))).doesNotContainAnyElementsOf(authorizationValues(sent.requests.get(1)));
    }

    // ── 대상 이름 ────────────────────────────────────────────────

    @Test
    @DisplayName("Feign 대상이 없으면 토큰 없이 보내지 않고 실패한다")
    void failsWithoutTarget() {
        Request request = Request.create(Request.HttpMethod.GET, "http://product-service/internal/v1/x", Map.of(), null, null, new RequestTemplate());

        assertThatThrownBy(() -> capability.withServiceToken(request)).isInstanceOf(IllegalStateException.class).hasMessageContaining("@FeignClient(name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    @DisplayName("Feign 대상 이름이 비었으면 실패한다")
    void failsWithBlankTargetName(String name) {
        assertThatThrownBy(() -> capability.withServiceToken(requestFor(name))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("대상 이름이 서비스 ID 모양(소문자 kebab)이 아니면(대문자 · URL) 발급기가 거부한다")
    void failsWithNonServiceIdTargetName() {
        assertThatThrownBy(() -> capability.withServiceToken(requestFor("PRODUCT-SERVICE"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> capability.withServiceToken(requestFor("https://api.tosspayments.com"))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 한계를 사실대로 고정한다 — 이름이 kebab 이면 외부 API 클라이언트에 붙여도 막지 못한다. URL 검사는 로컬 url override 를 막아 두지 않는다. */
    @Test
    @DisplayName("kebab 이름을 가진 외부 클라이언트(toss-payments, url 지정)에 붙이면 막지 못하고 토큰이 실린다 — 외부 클라이언트에 붙이지 않는 것은 리뷰 몫이다")
    void kebabNamedExternalClientIsNotDetected() {
        Request request = Request.create(Request.HttpMethod.POST, "https://api.tosspayments.com/v1/payments/confirm", Map.of(), null, null,
            templateFor(new StubTarget("toss-payments", "https://api.tosspayments.com")));

        assertThat(authorizationValues(capability.withServiceToken(request))).singleElement().asString().startsWith("Bearer ");
    }

    // ── 설정 클래스 ──────────────────────────────────────────────

    @Test
    @DisplayName("InternalFeignClientConfiguration 은 @Configuration 이 아니고, 서비스 컨텍스트의 발급기로 Capability 하나를 만든다 — 인터셉터 빈은 없다")
    void configurationRegistersCapabilityFromIssuer() {
        assertThat(InternalFeignClientConfiguration.class.isAnnotationPresent(Configuration.class)).as("전역 설정으로 스캔되지 않는다").isFalse();

        new ApplicationContextRunner()
            .withBean(InternalTokenIssuer.class, () -> issuer)
            .withUserConfiguration(InternalFeignClientConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(Capability.class).values()).singleElement().isInstanceOf(InternalServiceTokenCapability.class);
                assertThat(context.getBeansOfType(RequestInterceptor.class)).isEmpty();
            });
    }

    @Test
    @DisplayName("발급기 빈이 없으면 클라이언트 설정이 실패한다 — 서비스가 InternalTokenIssuerConfigurer 를 빠뜨린 경우")
    void configurationFailsWithoutIssuer() {
        new ApplicationContextRunner()
            .withUserConfiguration(InternalFeignClientConfiguration.class)
            .run(context -> assertThat(context).hasFailed());
    }

    // ── 도우미 ────────────────────────────────────────────────────

    /**
     * Client 단계에서 {@code Authorization} 을 더하는 다른 Capability. Feign 은 {@code enrich} 를 리플렉션으로 부르므로 public 클래스여야 한다.
     */
    public static final class HeaderAddingCapability implements Capability {

        @Override
        public Client enrich(Client delegate) {
            return (request, options) -> {
                Map<String, Collection<String>> headers = new LinkedHashMap<>(request.headers());
                headers.put("Authorization", List.of("Bearer leaked-by-capability"));
                return delegate.execute(
                    Request.create(request.httpMethod(), request.url(), headers, request.body(), request.charset(), request.requestTemplate()), options);
            };
        }
    }

    interface ProductStockClient {

        @RequestLine("GET /internal/v1/stocks?skuIds={skuIds}")
        String stocks(@Param("skuIds") String skuIds);
    }

    /** 보낸 요청을 붙잡고 200 "ok" 로 답한다. */
    private static final class CapturingClient implements Client {

        private final List<Request> requests = new ArrayList<>();

        @Override
        public Response execute(Request request, Request.Options options) {
            requests.add(request);
            return Response.builder().status(200).reason("OK").request(request).headers(Map.of()).body("ok", StandardCharsets.UTF_8).build();
        }
    }

    private static List<String> authorizationValues(Request request) {
        return request.headers().entrySet().stream()
            .filter(header -> header.getKey().equalsIgnoreCase("Authorization"))
            .flatMap(header -> header.getValue().stream())
            .toList();
    }

    private static Request requestFor(String targetName) {
        return Request.create(Request.HttpMethod.GET, "http://x/internal/v1/x", Map.of(), null, null, templateFor(new StubTarget(targetName, "http://x")));
    }

    private static RequestTemplate templateFor(Target<?> target) {
        RequestTemplate template = new RequestTemplate();
        template.feignTarget(target);
        return template;
    }

    /** {@code HardCodedTarget} 은 빈 이름을 생성자에서 막으므로, 이름이 빈 대상은 직접 만든다. 이름 · URL 말고는 쓰지 않는다. */
    private record StubTarget(String name, String url) implements Target<Object> {

        @Override
        public Class<Object> type() {
            return Object.class;
        }

        @Override
        public Request apply(RequestTemplate input) {
            throw new UnsupportedOperationException();
        }
    }

    private static Jwt decodeAs(String serviceId, String token) {
        return InternalTokenDecoderFactory.create(TestServiceTokens.propertiesWithoutPrevious(serviceId), Clock.fixed(Instant.now(), ZoneOffset.UTC))
            .decode(token);
    }
}
