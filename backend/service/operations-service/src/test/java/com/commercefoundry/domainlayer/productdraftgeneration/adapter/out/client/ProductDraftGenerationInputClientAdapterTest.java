package com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.productdraftgeneration.application.model.ProductDraftGenerationRemoteFailure;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.ProductDraftGenerationInputPort;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationInputQueryResult;
import com.commercefoundry.security.internal.InternalTokenDecoderFactory;
import com.commercefoundry.security.support.TestServiceTokens;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 Feign(서비스 토큰 · Spring 디코더 · 오류 디코더) → WireMock product. 본문은 product PR-2a(#703) 의 {@code ProductDraftGenerationInputResponse} ·
 * {@code ProductDraftInternalExceptionHandler} 응답 모양 그대로다.
 */
@SpringBootTest(properties = {"operations.jobs.worker.enabled=false", "operations.jobs.planner.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:product-draft-generation-input;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;"
        + "INIT=CREATE SCHEMA IF NOT EXISTS operations"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ProductDraftGenerationInputClientAdapterTest {

    private static final String INPUT = "/internal/v1/product-drafts/7/generation-input";
    private static final String IMAGE_0 = "/internal/v1/product-drafts/7/generation-images/0";
    private static final String IMAGE_1 = "/internal/v1/product-drafts/7/generation-images/1";
    private static final String SENTINEL = "SENTINEL_PRIVATE_PRODUCT_BODY";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x10, 0x00};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x7F};
    private static final WireMockServer SERVER = start();

    @Autowired
    ProductDraftGenerationInputPort port;
    @Autowired
    CircuitBreakerRegistry circuits;
    @Autowired
    Environment environment;

    private static WireMockServer start() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void url(DynamicPropertyRegistry properties) {
        properties.add("spring.cloud.openfeign.client.config.productDraftGenerationInputClient.url", SERVER::baseUrl);
    }

    @BeforeEach
    void reset() {
        SERVER.resetAll();
        circuits.circuitBreaker("product-service").reset();
    }

    @AfterAll
    static void close() {
        SERVER.stop();
    }

    /** product {@code ProductDraftInternalPresenter} 가 만드는 성공 봉투. */
    static String input() {
        return """
            {"dataHeader":{"success":true,"resultCode":null,"resultMessage":null,"fieldErrors":null},
             "dataBody":{"draftId":"7","status":"GENERATING","expiresAt":"2026-11-18T03:04:05.123456Z",
              "facts":{"nameHint":"린넨 셔츠","materials":["면 100%"],"careNotes":["찬물 손세탁"],"measurementsNote":"총장 70\\n가슴 50","origin":"대한민국",
               "certifications":[],"tone":null,"mustInclude":["린넨"],"mustAvoid":[]},
              "imageCount":2,
              "images":[{"index":0,"imageType":"THUMBNAIL","contentType":"image/jpeg","byteLength":5,"width":800,"height":1200},
                        {"index":1,"imageType":"GALLERY","contentType":"image/png","byteLength":9,"width":null,"height":null}]}}
            """;
    }

    /** product {@code ProductDraftInternalExceptionHandler} 가 만드는 오류 봉투. */
    static String error(String code) {
        return "{\"dataHeader\":{\"success\":false,\"resultCode\":\"" + code + "\",\"resultMessage\":\"" + SENTINEL + "\",\"fieldErrors\":null},\"dataBody\":null}";
    }

    @Test
    void decodesPr703InputShapeWithScopedServiceTokenAndOwnTimeout() {
        json(INPUT, 200, input());

        ProductDraftGenerationInputQueryResult result = port.getGenerationInput(7);

        assertThat(result.draftId()).isEqualTo(7);
        assertThat(result.status()).isEqualTo("GENERATING");
        assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-11-18T03:04:05.123456Z"));
        assertThat(result.facts().nameHint()).isEqualTo("린넨 셔츠");
        assertThat(result.facts().materials()).containsExactly("면 100%");
        assertThat(result.facts().measurementsNote()).isEqualTo("총장 70\n가슴 50");
        assertThat(result.facts().tone()).isNull();
        assertThat(result.facts().certifications()).isEmpty();
        assertThat(result.imageCount()).isEqualTo(2);
        assertThat(result.images()).containsExactly(new ProductDraftGenerationInputQueryResult.Image(0, "THUMBNAIL", "image/jpeg", 5, 800, 1200),
            new ProductDraftGenerationInputQueryResult.Image(1, "GALLERY", "image/png", 9, null, null));
        assertThat(result.toString()).doesNotContain("린넨");

        var requests = SERVER.findAll(getRequestedFor(urlEqualTo(INPUT)));
        assertThat(requests).hasSize(1);
        var jwt = InternalTokenDecoderFactory.create(TestServiceTokens.properties("product-service"), Clock.systemUTC())
            .decode(requests.getFirst().getHeader("Authorization").substring(7));
        assertThat(jwt.getAudience()).containsExactly("product-service");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("operations-service");
        assertThat(environment.getProperty("spring.cloud.openfeign.client.config.productDraftGenerationInputClient.read-timeout")).isEqualTo("10000");
    }

    @Test
    void imageEndpointDecodesOctetStreamBytesAsByteArrayInMetadataOrder() {
        bytes(IMAGE_0, JPEG);
        bytes(IMAGE_1, PNG);

        var images = port.getGenerationImages(7, List.of(new ProductDraftGenerationInputQueryResult.Image(0, "THUMBNAIL", "image/jpeg", 5, null, null),
            new ProductDraftGenerationInputQueryResult.Image(1, "GALLERY", "image/png", 9, null, null)));

        assertThat(images).hasSize(2);
        assertThat(images.get(0).bytes()).isEqualTo(JPEG);
        assertThat(images.get(0).mediaType()).isEqualTo("image/jpeg");
        assertThat(images.get(1).bytes()).isEqualTo(PNG);
        assertThat(images.get(1).index()).isEqualTo(1);
        SERVER.verify(1, getRequestedFor(urlEqualTo(IMAGE_0)));
        SERVER.verify(1, getRequestedFor(urlEqualTo(IMAGE_1)));
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
        "410, PRODUCT_DRAFT_044, PRODUCT_DRAFT_IMAGE_CHANGED, false",
        "409, PRODUCT_DRAFT_022, PRODUCT_DRAFT_INPUT_EXPIRED, false",
        "409, PRODUCT_DRAFT_041, PRODUCT_DRAFT_INPUT_REJECTED, false",
        "409, PRODUCT_DRAFT_042, PRODUCT_DRAFT_INPUT_REJECTED, false",
        "409, PRODUCT_DRAFT_043, PRODUCT_DRAFT_INPUT_REJECTED, false",
        "404, PRODUCT_DRAFT_001, PRODUCT_DRAFT_INPUT_REJECTED, false",
        "404, PRODUCT_DRAFT_045, PRODUCT_DRAFT_INPUT_REJECTED, false",
        "400, PRODUCT_DRAFT_040, PRODUCT_DRAFT_INPUT_REJECTED, false",
        "403, SECURITY_005, PRODUCT_DRAFT_INPUT_REJECTED, false",
        "503, PRODUCT_DRAFT_501, PRODUCT_DRAFT_INPUT_UNAVAILABLE, true",
        "500, PRODUCT_DRAFT_044, PRODUCT_DRAFT_INPUT_UNAVAILABLE, true"})
    void errorEnvelopesOnBothEndpointsMapToFixedCodes(int status, String productCode, String expected, boolean retryable, CapturedOutput output) {
        json(INPUT, status, error(productCode));
        json(IMAGE_0, status, error(productCode));

        failure(expected, retryable, () -> port.getGenerationInput(7));
        failure(expected, retryable, () -> port.getGenerationImages(7, List.of(new ProductDraftGenerationInputQueryResult.Image(0, "THUMBNAIL", "image/jpeg", 5,
            null, null))));
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }

    @Test
    void unreadableErrorBodyIsStillTerminalRejection() {
        SERVER.stubFor(get(urlEqualTo(INPUT)).willReturn(aResponse().withStatus(410).withHeader("Content-Type", "text/html").withBody("<html>" + SENTINEL)));
        failure("PRODUCT_DRAFT_INPUT_REJECTED", false, () -> port.getGenerationInput(7));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"dataHeader\":{\"success\":false},\"dataBody\":null}",
        "{\"dataHeader\":{\"success\":true},\"dataBody\":null}",
        "{\"dataHeader\":{\"success\":true},\"dataBody\":{\"draftId\":\"07\",\"status\":\"REQUESTED\",\"facts\":{},\"imageCount\":0,\"images\":[]}}",
        "{\"dataHeader\":{\"success\":true},\"dataBody\":{\"draftId\":\"7\",\"status\":\"REQUESTED\",\"imageCount\":0,\"images\":[]}}",
        "{\"dataHeader\":{\"success\":true},\"dataBody\":{\"draftId\":\"7\",\"status\":\"REQUESTED\",\"facts\":{},\"images\":[]}}",
        "{\"dataHeader\":{\"success\":true},\"dataBody\":{\"draftId\":\"7\",\"status\":\"REQUESTED\",\"facts\":{},\"imageCount\":1,\"images\":[null]}}",
        "{\"dataHeader\":{\"success\":true},\"dataBody\":{\"draftId\":\"7\",\"status\":\"REQUESTED\",\"facts\":{},\"imageCount\":1,\"images\":[{\"index\":0}]}}",
        "{\"dataHeader\":{\"success\":true},\"dataBody\":{\"draftId\":\"7\",\"status\":\"REQUESTED\",\"facts\":{\"materials\":[null]},\"imageCount\":0,\"images\":[]}}"})
    void successEnvelopeOutsideTheContractIsTerminalInvalid(String body) {
        json(INPUT, 200, body);
        failure("PRODUCT_DRAFT_INPUT_INVALID", false, () -> port.getGenerationInput(7));
    }

    @Test
    void openCircuitIsRetryableWithoutCallingProduct() {
        circuits.circuitBreaker("product-service").transitionToOpenState();
        failure("PRODUCT_DRAFT_INPUT_UNAVAILABLE", true, () -> port.getGenerationInput(7));
        SERVER.verify(0, getRequestedFor(urlEqualTo(INPUT)));
    }

    private static void json(String path, int status, String body) {
        SERVER.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static void bytes(String path, byte[] body) {
        SERVER.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/octet-stream")
            .withHeader("X-Content-Type-Options", "nosniff").withHeader("Cache-Control", "no-store").withBody(body)));
    }

    private static void failure(String code, boolean retryable, Executable call) {
        assertThatThrownBy(call::execute).isInstanceOfSatisfying(ProductDraftGenerationRemoteFailure.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(code);
            assertThat(failure.isRetryable()).isEqualTo(retryable);
            assertThat(failure.getMessage()).isNull();
            assertThat(failure.getCause()).isNull();
            assertThat(failure.toString()).doesNotContain(SENTINEL);
        });
    }
}
