package com.commercefoundry.domainlayer.productdraft.adapter.out.client;

import static com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode.GENERATION_CONTRACT_VIOLATION;
import static com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode.GENERATION_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.productdraft.adapter.out.client.feign.ProductDraftGenerationClient;
import com.commercefoundry.domainlayer.productdraft.adapter.out.client.feign.ProductDraftGenerationFeignConfiguration;
import com.commercefoundry.domainlayer.productdraft.adapter.out.client.support.ProductDraftGenerationResultResponseSupport;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftGenerationResultQueryResult;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftGenerationResultQueryResult.FailureCode;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftGenerationResultQueryResult.Status;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftGeneration;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageType;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
import feign.Client;
import feign.Feign;
import feign.Request;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import tools.jackson.databind.json.JsonMapper;

class ProductDraftGenerationResultClientAdapterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HASH = "f".repeat(64);
    private static final String MARKER = "private-source-marker";
    private static final String RESULT = """
        {"suggestion":{"name":"면 셔츠","description":"첫 줄\\n둘째 줄","searchTags":["면"],"seoTitle":"면 셔츠","seoDescription":"면 소개",
        "imageAlts":[{"index":0,"altText":"앞모습"}]},"unconfirmedFacts":["CARE_NOTES","MEASUREMENTS","ORIGIN","CERTIFICATIONS"],
        "warnings":[{"code":"MUST_INCLUDE_MISSING","index":0}]}
        """;
    private static final ProductDraft DRAFT = new ProductDraft(7L, ProductDraftStatus.GENERATING, 100L,
        new ProductDraftFacts(null, null, List.of("면"), null, null, null, null, null, List.of("단추"), null),
        List.of(new ProductDraftImage("https://media.example/product.jpg", ProductDraftImageType.THUMBNAIL, null, null)), null, null,
        Instant.parse("2026-11-24T03:00:00Z"), Instant.parse("2026-11-17T03:00:00Z"),
        new ProductDraftGeneration(1, 200L, Instant.parse("2026-11-17T03:00:01Z"), HASH, 31L));

    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();

    @Test
    void getsOnlyTheBoundJobAndReturnsSanitizedSuggestion() {
        AtomicInteger calls = new AtomicInteger();
        var adapter = adapter((request, options) -> {
            calls.incrementAndGet();
            assertThat(request.httpMethod()).isEqualTo(Request.HttpMethod.GET);
            assertThat(request.url()).isEqualTo("http://operations-service/internal/v1/product-draft-generation-jobs/31/result");
            assertThat(request.body()).isNull();
            assertThat(options.connectTimeoutMillis()).isEqualTo(2000);
            assertThat(options.readTimeoutMillis()).isEqualTo(3000);
            assertThat(options.isFollowRedirects()).isFalse();
            String sensitive = RESULT.replace("앞모습", "앞모습 👩‍🎨 ０１０-１２３４-５６７８")
                .replace("면 소개", "면 소개 shop@example.test")
                .replace("[\"면\"]", "[\"ＦＩＴ\",\"fit\"]");
            return raw(request, 200, envelope("SUCCEEDED", null, sensitive));
        });

        var result = adapter.result(DRAFT);

        assertThat(calls).hasValue(1);
        assertThat(result.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(result.failureCode()).isNull();
        assertThat(result.draftId()).isEqualTo(7);
        assertThat(result.jobId()).isEqualTo(31);
        assertThat(result.generationNo()).isEqualTo(1);
        assertThat(result.inputHash()).isEqualTo(HASH);
        assertThat(result.candidate().suggestion().description()).isEqualTo("첫 줄\n둘째 줄");
        assertThat(result.candidate().suggestion().searchTags()).containsExactly("FIT");
        assertThat(result.candidate().suggestion().seoDescription()).isEqualTo("면 소개 [EMAIL]");
        assertThat(result.candidate().suggestion().imageAlts().getFirst().altText()).isEqualTo("앞모습 👩‍🎨 [PHONE]");
        assertThat(result.candidate().warnings()).hasSize(1);
        assertThat(result.candidate().unconfirmedFacts()).hasSize(4);
        assertThat(result.toString()).doesNotContain(HASH, "면 셔츠");
        assertThat(DRAFT.facts().materials()).containsExactly("면");
    }

    @ParameterizedTest
    @CsvSource({"PENDING,", "CANCELLED,", "FAILED,PRODUCT_DRAFT_AI_BUDGET_EXCEEDED", "FAILED,LEASE_EXPIRED", "FAILED,GENERATION_FAILED"})
    void acceptsOnlyExpectedStateFields(Status status, FailureCode failureCode) {
        var result = query(200, envelope(status.name(), failureCode, null));
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.failureCode()).isEqualTo(failureCode);
        assertThat(result.candidate()).isNull();
    }

    @Test
    void rejectsDraftWithoutAJobBeforeTheCall() {
        var adapter = adapter((request, options) -> {
            throw new AssertionError("unexpected HTTP request");
        });
        var draft = ProductDraft.request(7L, 100L, DRAFT.facts(), DRAFT.images(), DRAFT.expiresAt());

        assertThatThrownBy(() -> adapter.result(draft)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "\"draftId\":\"7\"|\"draftId\":\"8\"", "\"draftId\":\"7\"|\"draftId\":7",
        "\"generationNo\":1|\"generationNo\":2", "\"generationNo\":1|\"generationNo\":\"1\"",
        "\"jobId\":\"31\"|\"jobId\":\"32\"", "\"inputHash\":\"|\"inputHash\":\"b",
        "\"PENDING\"|\"EXPIRED\"", "\"PENDING\"|\"RUNNING\"", "\"PENDING\"|\"FAILED\"", "\"PENDING\"|\"SUCCEEDED\"",
        "\"resultJson\":null|\"resultJson\":\"private-source-marker\"",
        "\"failureCode\":null|\"failureCode\":\"LEASE_EXPIRED\"",
        "\"failureCode\":null|\"unknown\":null", "\"success\":true|\"success\":\"true\"",
        "\"success\":true|\"success\":true,\"success\":false"
    })
    void rejectsBindingStateAndEnvelopeViolations(String from, String to) {
        String malformed = envelope("PENDING", null, null).replace(from, to);
        assertCode(() -> query(200, malformed), GENERATION_CONTRACT_VIOLATION);
    }

    @Test
    void rejectsUnrecognizedFailureCodeWithoutLeakingIt() {
        String body = envelope("FAILED", FailureCode.GENERATION_FAILED, null).replace("GENERATION_FAILED", MARKER);
        assertCode(() -> query(200, body), GENERATION_CONTRACT_VIOLATION);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "\"앞모습\"|\"<b>private-source-marker</b>\"", "\"앞모습\"|\"＜b＞\"", "\"앞모습\"|\"ｗｗｗ.example.test\"",
        "\"앞모습\"|\"bad\\u202evalue\"", "\"앞모습\"|\"bad\\uD800value\"", "\"앞모습\"|999",
        "\"앞모습\"|\"010\\u200B-1234-5678\"", "\"앞모습\"|\"shop\\uFEFF@example.test\"",
        "\"앞모습\"|\"010\\u200D-1234-5678\"", "\"앞모습\"|\"shop\\uFE0F@example.test\"",
        "\"앞모습\"|\"ww\\u200Dw.example.test\"",
        "\"index\":0|\"index\":1", "\"index\":0|\"index\":\"0\"",
        "\"altText\"|\"origin\"", "\"name\"|\"materials\"", "\"CARE_NOTES\"|\"MATERIALS\"",
        "\"MUST_INCLUDE_MISSING\"|\"MUST_AVOID_USED\""
    })
    void rejectsMalformedOrUnsafeCandidate(String from, String to) {
        // 위 값은 원격 JSON 이스케이프를 포함할 수 있다.
        String malformed = RESULT.replace(from, to);
        assertThat(malformed).isNotEqualTo(RESULT);
        assertCode(() -> query(200, envelope("SUCCEEDED", null, malformed)), GENERATION_CONTRACT_VIOLATION);
    }

    @Test
    void acceptsExactInnerUtf8LimitAndRejectsOneByteMore() {
        String wide = RESULT.replace("첫 줄", "가".repeat(3000));
        String boundary = wide + " ".repeat(32 * 1024 - wide.getBytes(StandardCharsets.UTF_8).length);
        assertThat(query(200, envelope("SUCCEEDED", null, boundary)).status()).isEqualTo(Status.SUCCEEDED);
        assertCode(() -> query(200, envelope("SUCCEEDED", null, boundary + " ")), GENERATION_CONTRACT_VIOLATION);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 128})
    void stopsReadingOversizedStreamAndClosesIt(int declaredLength) {
        int limit = 6 * 32 * 1024 + 4096;
        AtomicInteger bytes = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        var stream = new ByteArrayInputStream(new byte[limit + 1000]) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                int read = super.read(buffer, offset, length);
                bytes.addAndGet(Math.max(read, 0));
                return read;
            }

            @Override
            public void close() throws IOException {
                closes.incrementAndGet();
                super.close();
            }
        };
        // 작은 압축 전송 길이를 보고 Feign 이 풀린 스트림을 먼저 끝까지 읽는 경로도 재현한다.
        var adapter = adapter((request, options) -> feign.Response.builder().request(request).status(200)
            .body(stream, declaredLength == 0 ? null : declaredLength).build());

        assertCode(() -> adapter.result(DRAFT), GENERATION_CONTRACT_VIOLATION);
        assertThat(bytes).hasValue(limit + 1);
        assertThat(closes.get()).isPositive();
    }

    @Test
    void distinguishesExactRetentionExpiryFromOtherErrors() {
        String expired = JSON.writeValueAsString(Response.fail("PRODUCT_DRAFT_GENERATION_010", MARKER));
        assertThat(query(410, expired).status()).isEqualTo(Status.EXPIRED);
        assertCode(() -> query(410, expired.replace("_010", "_001")), GENERATION_CONTRACT_VIOLATION);
        assertCode(() -> query(410, expired + " ".repeat(4096)), GENERATION_CONTRACT_VIOLATION);
        assertCode(() -> query(404, expired), GENERATION_CONTRACT_VIOLATION);
        String invalid = JSON.writeValueAsString(Response.fail("PRODUCT_DRAFT_GENERATION_503", MARKER));
        assertCode(() -> query(502, invalid), GENERATION_CONTRACT_VIOLATION);
        assertCode(() -> query(502, MARKER), GENERATION_UNAVAILABLE);
        assertCode(() -> query(503, invalid), GENERATION_UNAVAILABLE);
        assertCode(() -> query(403, invalid), GENERATION_UNAVAILABLE);
        assertThat(registry.circuitBreaker("product-draft-generation-result-operations-service").getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
        assertThat(registry.circuitBreaker("product-draft-generation-operations-service").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void timeoutIsNotRetriedAndAnOpenCircuitMakesNoCall() {
        AtomicInteger calls = new AtomicInteger();
        var adapter = adapter((request, options) -> {
            calls.incrementAndGet();
            throw new SocketTimeoutException(MARKER);
        });
        assertCode(() -> adapter.result(DRAFT), GENERATION_UNAVAILABLE);
        assertThat(calls).hasValue(1);
        registry.circuitBreaker("product-draft-generation-result-operations-service").transitionToOpenState();
        assertCode(() -> adapter.result(DRAFT), GENERATION_UNAVAILABLE);
        assertThat(calls).hasValue(1);
    }

    private static String envelope(String status, FailureCode failureCode, String json) {
        return JSON.writeValueAsString(Response.success(new WireResult("7", 1, "31", HASH, status, failureCode, json)));
    }

    private ProductDraftGenerationResultQueryResult query(int status, String body) {
        return adapter((request, options) -> raw(request, status, body)).result(DRAFT);
    }

    private ProductDraftGenerationResultClientAdapter adapter(Client transport) {
        var config = new ProductDraftGenerationFeignConfiguration();
        ProductDraftGenerationClient client = Feign.builder().contract(new SpringMvcContract()).client(transport)
            .options(config.productDraftGenerationOptions()).retryer(config.productDraftGenerationRetryer())
            .responseInterceptor(config.productDraftGenerationResultStream())
            .target(ProductDraftGenerationClient.class, "http://operations-service");
        return new ProductDraftGenerationResultClientAdapter(client, new ProductDraftGenerationResultResponseSupport(registry));
    }

    private static feign.Response raw(Request request, int status, String body) {
        return feign.Response.builder().request(request).status(status).body(body, StandardCharsets.UTF_8).build();
    }

    private static void assertCode(Runnable call, ProductDraftErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ProductDraftException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(code);
            assertThat(exception.getCause()).isNull();
            assertThat(exception.toString()).doesNotContain(MARKER);
        });
    }

    private record WireResult(String draftId, int generationNo, String jobId, String inputHash, String status, FailureCode failureCode, String resultJson) {
    }
}
