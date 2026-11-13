package com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client.feign.ProductDraftGenerationInputClient;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client.support.ProductDraftGenerationInputInternalResponseSupport;
import com.commercefoundry.domainlayer.productdraftgeneration.application.model.ProductDraftGenerationRemoteFailure;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.ProductDraftGenerationInputPort.ImageRequest;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationImageQueryResult;
import feign.FeignException;
import feign.Request;
import feign.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원시 응답 스트림 경계 — 실제 HTTP 로는 만들 수 없는 경우(선언보다 긴 본문 · 읽다 끊김)와 "읽은 바이트 수 · 스트림 닫힘" 을 Feign 응답을 직접 만들어 본다.
 * 실제 Feign · 서킷 설정 경로는 {@link ProductDraftGenerationInputClientAdapterTest}(WireMock) 가 본다.
 */
class ProductDraftGenerationInputClientAdapterStreamTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x10, 0x00};
    private static final ImageRequest JPEG_REQUEST = new ImageRequest(0, JPEG.length, "image/jpeg");

    private final ProductDraftGenerationInputClient client = mock(ProductDraftGenerationInputClient.class);
    /** yml 기본 설정과 같은 ignore-exceptions. */
    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
        .ignoreExceptions(FeignException.FeignClientException.class).build());
    private final ProductDraftGenerationInputClientAdapter adapter = new ProductDraftGenerationInputClientAdapter(client,
        new ProductDraftGenerationInputInternalResponseSupport(registry, JsonMapper.builder().build()));
    private final List<ProductDraftGenerationImageQueryResult> received = new ArrayList<>();

    @Test
    void readsExactlyTheDeclaredBytesAndClosesTheStream() {
        var body = new TrackingStream(JPEG);
        respond(200, headers("application/octet-stream", "5"), body);

        adapter.readGenerationImages(7, List.of(JPEG_REQUEST), received::add);

        assertThat(received).singleElement().satisfies(image -> {
            assertThat(image.bytes()).isEqualTo(JPEG);
            assertThat(image.mediaType()).isEqualTo("application/octet-stream");
        });
        assertThat(body.read).isEqualTo(JPEG.length);
        assertThat(body.closed).isTrue();
    }

    @Test
    void bodyLongerThanDeclaredStopsAfterOneExtraByte() {
        byte[] longer = Arrays.copyOf(JPEG, 1024 * 1024);
        var body = new TrackingStream(longer);
        respond(200, headers("application/octet-stream", "5"), body);

        imageChanged(() -> adapter.readGenerationImages(7, List.of(JPEG_REQUEST), received::add));

        assertThat(body.read).isEqualTo(JPEG.length + 1);
        assertThat(body.closed).isTrue();
        assertThat(received).isEmpty();
        // product 는 응답했다 — 바뀐 이미지는 서킷 실패가 아니다
        assertThat(registry.circuitBreaker("product-service").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void bodyShorterThanDeclaredIsChanged() {
        var body = new TrackingStream(Arrays.copyOf(JPEG, 3));
        respond(200, headers("application/octet-stream", "5"), body);
        imageChanged(() -> adapter.readGenerationImages(7, List.of(JPEG_REQUEST), received::add));
        assertThat(body.closed).isTrue();
    }

    @Test
    void missingWrongOrRepeatedContentLengthIsChangedWithoutReadingTheBody() {
        for (Map<String, Collection<String>> headers : List.of(
            headers("application/octet-stream", null),
            headers("application/octet-stream", "6"),
            headers("application/octet-stream", "-5"),
            headers("application/octet-stream", "5x"),
            headers("application/octet-stream", "99999999999999999999"),
            with(headers("application/octet-stream", null), "Content-Length", List.of("5", "5")))) {
            var body = new TrackingStream(JPEG);
            respond(200, headers, body);

            imageChanged(() -> adapter.readGenerationImages(7, List.of(JPEG_REQUEST), received::add));

            assertThat(body.read).as(headers.toString()).isZero();
            assertThat(body.closed).as(headers.toString()).isTrue();
        }
        assertThat(received).isEmpty();
    }

    @Test
    void missingOrOtherContentTypeIsChangedWithoutReadingTheBody() {
        for (Map<String, Collection<String>> headers : List.of(
            headers(null, "5"),
            headers("image/png", "5"),
            headers("text/html; charset=utf-8", "5"),
            headers(";", "5"),
            with(headers(null, "5"), "Content-Type", List.of("application/octet-stream", "image/jpeg")))) {
            var body = new TrackingStream(JPEG);
            respond(200, headers, body);

            imageChanged(() -> adapter.readGenerationImages(7, List.of(JPEG_REQUEST), received::add));

            assertThat(body.read).as(headers.toString()).isZero();
            assertThat(body.closed).as(headers.toString()).isTrue();
        }
    }

    @Test
    void missingBodyIsChanged() {
        when(client.getGenerationImage(7, 0)).thenReturn(Response.builder().status(200).request(request()).headers(headers("application/octet-stream", "5"))
            .build());
        imageChanged(() -> adapter.readGenerationImages(7, List.of(JPEG_REQUEST), received::add));
    }

    @Test
    void connectionDroppedWhileReadingIsRetryableAndCountsAsCircuitFailure() {
        var body = new TrackingStream(JPEG) {
            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                throw new IOException("connection reset — " + "SENTINEL_PRIVATE");
            }
        };
        respond(200, headers("application/octet-stream", "5"), body);

        assertThatThrownBy(() -> adapter.readGenerationImages(7, List.of(JPEG_REQUEST), received::add))
            .isInstanceOfSatisfying(ProductDraftGenerationRemoteFailure.class, failure -> {
                assertThat(failure.getErrorCode()).isEqualTo("PRODUCT_DRAFT_INPUT_UNAVAILABLE");
                assertThat(failure.isRetryable()).isTrue();
                assertThat(failure.getCause()).isNull();
                assertThat(failure.getMessage()).isNull();
            });
        assertThat(body.closed).isTrue();
        assertThat(registry.circuitBreaker("product-service").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void errorBodyIsReadOnlyUpToTheLimit() {
        byte[] huge = new byte[1024 * 1024];
        Arrays.fill(huge, (byte) 'x');
        var body = new TrackingStream(huge);
        respond(503, headers("application/json", null), body);

        assertThatThrownBy(() -> adapter.readGenerationImages(7, List.of(JPEG_REQUEST), received::add))
            .isInstanceOfSatisfying(ProductDraftGenerationRemoteFailure.class, failure -> {
                assertThat(failure.getErrorCode()).isEqualTo("PRODUCT_DRAFT_INPUT_UNAVAILABLE");
                assertThat(failure.isRetryable()).isTrue();
            });
        assertThat(body.read).isLessThanOrEqualTo(4096 + 1);
        assertThat(body.closed).isTrue();
        assertThat(registry.circuitBreaker("product-service").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void clientErrorKeepsTheCodeFromABoundedBodyAndIsNotACircuitFailure() {
        String envelope = "{\"dataHeader\":{\"success\":false,\"resultCode\":\"PRODUCT_DRAFT_044\"},\"dataBody\":null}";
        var body = new TrackingStream(envelope.getBytes(StandardCharsets.UTF_8));
        respond(410, headers("application/json", null), body);

        imageChanged(() -> adapter.readGenerationImages(7, List.of(JPEG_REQUEST), received::add));

        assertThat(body.closed).isTrue();
        assertThat(registry.circuitBreaker("product-service").getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(registry.circuitBreaker("product-service").getMetrics().getNumberOfSuccessfulCalls()).isZero();
    }

    private void respond(int status, Map<String, Collection<String>> headers, TrackingStream body) {
        when(client.getGenerationImage(7, 0)).thenReturn(Response.builder().status(status).request(request()).headers(headers).body(body, null).build());
    }

    private static Request request() {
        return Request.create(Request.HttpMethod.GET, "http://product-service/internal/v1/product-drafts/7/generation-images/0", Map.of(), null,
            StandardCharsets.UTF_8, null);
    }

    private static Map<String, Collection<String>> headers(String contentType, String contentLength) {
        Map<String, Collection<String>> headers = new LinkedHashMap<>();
        if (contentType != null) {
            headers.put("Content-Type", List.of(contentType));
        }
        if (contentLength != null) {
            headers.put("Content-Length", List.of(contentLength));
        }
        return headers;
    }

    private static Map<String, Collection<String>> with(Map<String, Collection<String>> headers, String name, List<String> values) {
        headers.put(name, values);
        return headers;
    }

    private static void imageChanged(Executable call) {
        assertThatThrownBy(call::execute).isInstanceOfSatisfying(ProductDraftGenerationRemoteFailure.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo("PRODUCT_DRAFT_IMAGE_CHANGED");
            assertThat(failure.isRetryable()).isFalse();
        });
    }

    /** 읽은 바이트 수와 닫힘을 센다. */
    private static class TrackingStream extends InputStream {

        private final ByteArrayInputStream delegate;
        int read;
        boolean closed;

        TrackingStream(byte[] bytes) {
            this.delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                read++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                read += count;
            }
            return count;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
