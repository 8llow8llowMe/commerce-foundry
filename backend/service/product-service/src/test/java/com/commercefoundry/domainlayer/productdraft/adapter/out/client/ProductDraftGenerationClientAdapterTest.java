package com.commercefoundry.domainlayer.productdraft.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.productdraft.adapter.out.client.feign.ProductDraftGenerationClient;
import com.commercefoundry.domainlayer.productdraft.adapter.out.client.feign.ProductDraftGenerationFeignConfiguration;
import com.commercefoundry.domainlayer.productdraft.adapter.out.client.support.ProductDraftGenerationInternalResponseSupport;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftGeneration;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import feign.Client;
import feign.Feign;
import feign.Request;
import feign.codec.DecodeException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import tools.jackson.databind.json.JsonMapper;

/**
 * 상품 초안 생성 등록 Feign 계약 — 실제 Feign 계약 · 직렬화를 메모리 HTTP 클라이언트로 돌린다(발견 · 네트워크 없음, 선례: {@code SizeChartGenerationFeignTransportTest}).
 * 요청 칸 · 시간 제한 · 재시도 없음 · 응답 분류(#359 PR-B1 실패 표) · 4xx 를 서킷 실패로 세지 않음을 본다.
 */
class ProductDraftGenerationClientAdapterTest {

    private static final String HASH = "f".repeat(64);
    private static final Instant EXPIRES_AT = Instant.parse("2026-11-24T03:00:00.123456Z");
    private static final ProductDraft CLAIMED = new ProductDraft(7L, ProductDraftStatus.REQUESTED, 100L,
        new ProductDraftFacts(null, null, null, null, null, null, null, null, null, null), List.of(), null, null, EXPIRES_AT, Instant.parse("2026-11-17T03:00:00Z"),
        new ProductDraftGeneration(1, 200L, Instant.parse("2026-11-17T03:00:01Z"), HASH, null));

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ProductDraftGenerationFeignConfiguration config = new ProductDraftGenerationFeignConfiguration();
    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();

    @Test
    @DisplayName("POST 본문은 영수증 대조 칸 5개(draftId · generationNo · inputHash · actorMemberId · expiresAt)뿐이고 저장 값 그대로다 — 연결 2초 · 읽기 3초 · 리다이렉트 안 따름")
    void requestCarriesStoredReceiptFields() {
        AtomicReference<Request> captured = new AtomicReference<>();
        ProductDraftGenerationClientAdapter adapter = adapter((request, options) -> {
            captured.set(request);
            assertThat(options.connectTimeoutMillis()).isEqualTo(2000);
            assertThat(options.readTimeoutMillis()).isEqualTo(3000);
            assertThat(options.isFollowRedirects()).isFalse();
            return ok(request, registration("\"7\"", "1", "\"31\"", HASH));
        });

        var result = adapter.register(CLAIMED);

        assertThat(result.jobId()).isEqualTo(31L);
        assertThat(result.generationNo()).isEqualTo(1);
        assertThat(captured.get().httpMethod()).isEqualTo(Request.HttpMethod.POST);
        assertThat(captured.get().url()).isEqualTo("http://operations-service/internal/v1/product-draft-generation-jobs");
        var json = mapper.readTree(captured.get().body());
        assertThat(json.size()).isEqualTo(5);
        assertThat(json.get("draftId").longValue()).isEqualTo(7L);
        assertThat(json.get("generationNo").intValue()).isEqualTo(1);
        assertThat(json.get("inputHash").stringValue()).isEqualTo(HASH);
        assertThat(json.get("actorMemberId").longValue()).as("다시 누른 어드민이 아니라 claim 한 어드민").isEqualTo(200L);
        assertThat(mapper.treeToValue(json.get("expiresAt"), Instant.class)).isEqualTo(EXPIRES_AT);
    }

    @Test
    @DisplayName("작업을 기다리는 회차가 아니면(시작 전 · 생성 중) 부르지 않는다 — 호출 순서 버그다")
    void refusesUnclaimedDraft() {
        AtomicInteger calls = new AtomicInteger();
        ProductDraftGenerationClientAdapter adapter = adapter((request, options) -> {
            calls.incrementAndGet();
            return ok(request, registration("\"7\"", "1", "\"31\"", HASH));
        });
        ProductDraft unclaimed = new ProductDraft(7L, ProductDraftStatus.REQUESTED, 100L, CLAIMED.facts(), List.of(), null, null, EXPIRES_AT, null);

        assertThatThrownBy(() -> adapter.register(unclaimed)).isInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "draftId 다름|\"8\"|1|\"31\"|" + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "회차 다름|\"7\"|2|\"31\"|" + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "회차 없음|\"7\"|null|\"31\"|" + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "회차 소수|\"7\"|1.5|\"31\"|" + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "회차 문자열|\"7\"|\"1\"|\"31\"|" + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "hash 다름|\"7\"|1|\"31\"|" + "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
        "hash 대문자|\"7\"|1|\"31\"|" + "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
        "작업 0|\"7\"|1|\"0\"|" + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "작업 범위 밖|\"7\"|1|\"9223372036854775808\"|" + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "숫자 아이디|7|1|\"31\"|" + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    })
    @DisplayName("응답의 초안 · 회차 · hash 불일치와 형식 위반은 계약 위반 504 다")
    void bindingMismatch(String name, String draftId, String generationNo, String jobId, String hash) {
        ProductDraftGenerationClientAdapter adapter = adapter((request, options) -> ok(request, registration(draftId, generationNo, jobId, hash)));

        assertCode(() -> adapter.register(CLAIMED), ProductDraftErrorCode.GENERATION_CONTRACT_VIOLATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"dataHeader\":{\"success\":false,\"resultCode\":\"X\"},\"dataBody\":null}",
        "{\"dataHeader\":{\"success\":true},\"dataBody\":null}",
        "{\"dataBody\":{}}",
        "not-json"
    })
    @DisplayName("200 인데 봉투가 실패 · 비었음 · 깨짐이면 504 다")
    void badEnvelope(String body) {
        ProductDraftGenerationClientAdapter adapter = adapter((request, options) -> raw(request, 200, body));

        assertCode(() -> adapter.register(CLAIMED), ProductDraftErrorCode.GENERATION_CONTRACT_VIOLATION);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "409, PRODUCT_DRAFT_GENERATION_002, GENERATION_CONTRACT_VIOLATION",
        "409, PRODUCT_DRAFT_GENERATION_005, DRAFT_EXPIRED",
        "409, PRODUCT_DRAFT_GENERATION_999, GENERATION_CONTRACT_VIOLATION",
        "400, PRODUCT_DRAFT_GENERATION_121, GENERATION_CONTRACT_VIOLATION",
        "404, X, GENERATION_CONTRACT_VIOLATION",
        "401, X, GENERATION_UNAVAILABLE",
        "403, X, GENERATION_UNAVAILABLE",
        "408, X, GENERATION_UNAVAILABLE",
        "429, X, GENERATION_UNAVAILABLE",
        "500, X, GENERATION_UNAVAILABLE",
        "503, PRODUCT_DRAFT_GENERATION_501, GENERATION_UNAVAILABLE"
    })
    @DisplayName("오류 응답 분류 — 409 는 resultCode 로 가르고, 원격 메시지 · 본문을 예외에 싣지 않는다")
    void classifiesErrors(int status, String resultCode, ProductDraftErrorCode expected) {
        ProductDraftGenerationClientAdapter adapter = adapter((request, options) -> raw(request, status,
            "{\"dataHeader\":{\"success\":false,\"resultCode\":\"" + resultCode + "\",\"resultMessage\":\"private-source-marker\"},\"dataBody\":null}"));

        assertCode(() -> adapter.register(CLAIMED), expected);
    }

    @Test
    @DisplayName("409 인데 오류 본문을 읽을 수 없으면 계약 위반 504 다")
    void conflictWithoutReadableBody() {
        ProductDraftGenerationClientAdapter adapter = adapter((request, options) -> raw(request, 409, "<html>"));

        assertCode(() -> adapter.register(CLAIMED), ProductDraftErrorCode.GENERATION_CONTRACT_VIOLATION);
    }

    @Test
    @DisplayName("타임아웃은 503 대역 502 이고 자동 재시도하지 않는다")
    void timeoutIsUnavailableWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        ProductDraftGenerationClientAdapter adapter = adapter((request, options) -> {
            calls.incrementAndGet();
            throw new SocketTimeoutException("private-source-marker");
        });

        assertCode(() -> adapter.register(CLAIMED), ProductDraftErrorCode.GENERATION_UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("4xx 는 서킷 실패로 세지 않고 5xx 만 센다. 서킷이 열리면 부르지 않고 502 다")
    void circuitBreaker() {
        AtomicInteger calls = new AtomicInteger();
        ProductDraftGenerationClientAdapter adapter = adapter((request, options) -> {
            int call = calls.incrementAndGet();
            return raw(request, call == 1 ? 409 : call == 2 ? 400 : 500, "{\"dataHeader\":{\"success\":false,\"resultCode\":\"X\"}}");
        });
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> adapter.register(CLAIMED)).isInstanceOf(ProductDraftException.class);
        }
        assertThat(registry.circuitBreaker("product-draft-generation-operations-service").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(registry.circuitBreaker("operations-service").getMetrics().getNumberOfFailedCalls()).isZero();

        registry.circuitBreaker("product-draft-generation-operations-service").transitionToOpenState();
        assertCode(() -> adapter.register(CLAIMED), ProductDraftErrorCode.GENERATION_UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("서비스 토큰 설정 · contextId 를 쓰고 본문 로그 · 재시도가 꺼져 있다")
    void feignConfiguration() {
        FeignClient annotation = ProductDraftGenerationClient.class.getAnnotation(FeignClient.class);
        assertThat(annotation.configuration()).contains(InternalFeignClientConfiguration.class, ProductDraftGenerationFeignConfiguration.class);
        assertThat(annotation.contextId()).isEqualTo("productDraftGenerationClient");
        assertThat(annotation.name()).isEqualTo("${feign-client.target-services.operations-service:operations-service}");
        assertThat(config.productDraftGenerationLoggerLevel()).isEqualTo(feign.Logger.Level.NONE);
        assertThat(config.productDraftGenerationRetryer()).isSameAs(feign.Retryer.NEVER_RETRY);
    }

    private ProductDraftGenerationClientAdapter adapter(Client transport) {
        ProductDraftGenerationClient client = Feign.builder().contract(new SpringMvcContract()).client(transport)
            .options(config.productDraftGenerationOptions()).retryer(config.productDraftGenerationRetryer())
            .encoder((object, type, template) -> template.body(mapper.writeValueAsBytes(object), StandardCharsets.UTF_8))
            .decoder((response, type) -> {
                try {
                    return mapper.readValue(response.body().asInputStream(), mapper.getTypeFactory().constructType(type));
                } catch (RuntimeException exception) {
                    throw new DecodeException(response.status(), "Invalid internal response", response.request());
                }
            }).target(ProductDraftGenerationClient.class, "http://operations-service");
        return new ProductDraftGenerationClientAdapter(client, new ProductDraftGenerationInternalResponseSupport(registry));
    }

    private static String registration(String draftId, String generationNo, String jobId, String hash) {
        return "{\"draftId\":" + draftId + ",\"generationNo\":" + generationNo + ",\"jobId\":" + jobId + ",\"inputHash\":\"" + hash + "\"}";
    }

    private static feign.Response ok(Request request, String body) {
        return raw(request, 200, "{\"dataHeader\":{\"success\":true},\"dataBody\":" + body + "}");
    }

    private static feign.Response raw(Request request, int status, String body) {
        return feign.Response.builder().request(request).status(status).reason("R").body(body, StandardCharsets.UTF_8).build();
    }

    private static void assertCode(Runnable call, ProductDraftErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ProductDraftException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(code);
            assertThat(exception.getCause()).isNull();
            assertThat(exception.toString()).doesNotContain("private-source-marker");
        });
    }
}
