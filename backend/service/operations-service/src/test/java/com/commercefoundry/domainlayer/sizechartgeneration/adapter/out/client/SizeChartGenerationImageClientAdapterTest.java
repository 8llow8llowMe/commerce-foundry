package com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.feign.SizeChartGenerationInputClient;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.feign.dto.SizeChartGenerationInputInternalResponse;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.support.SizeChartGenerationInputInternalResponseSupport;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationImageQueryResult;
import feign.Request;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import tools.jackson.databind.json.JsonMapper;

class SizeChartGenerationImageClientAdapterTest {
    private static final int MAX = 5 * 1024 * 1024;
    private final SizeChartGenerationInputClient client = mock(SizeChartGenerationInputClient.class);
    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
    private final SizeChartGenerationInputClientAdapter adapter = new SizeChartGenerationInputClientAdapter(client,
        new SizeChartGenerationInputInternalResponseSupport(registry, JsonMapper.builder().build()));

    @Test
    void readsImagesInOrderAndHandsEachOverBeforeTheNextRead() throws Exception {
        assertThat(SizeChartGenerationInputClient.class.getMethod("getGenerationImage", long.class, int.class)
            .getAnnotation(GetMapping.class).value()).containsExactly("/internal/v1/size-chart-drafts/{draftId}/generation-images/{index}");
        when(client.getGenerationImage(7, 0)).thenReturn(image(200, new byte[] {1, 2, 3}, "3", "application/octet-stream"));
        when(client.getGenerationImage(7, 1)).thenReturn(image(200, new byte[] {4, 5}, "2", "application/octet-stream; charset=binary"));
        List<SizeChartGenerationImageQueryResult> received = new ArrayList<>();
        adapter.readGenerationImages(7, List.of(0, 1), MAX, received::add);
        assertThat(received).extracting(SizeChartGenerationImageQueryResult::index).containsExactly(0, 1);
        assertThat(received.get(0).bytes()).containsExactly(1, 2, 3);
        assertThat(received.get(0).toString()).doesNotContain("[1, 2, 3]");
    }

    @Test
    void callbackFailureStopsBeforeTheNextImage() {
        when(client.getGenerationImage(7, 0)).thenReturn(image(200, new byte[] {1}, "1", "application/octet-stream"));
        var stop = new IllegalStateException("stop");
        assertThatThrownBy(() -> adapter.readGenerationImages(7, List.of(0, 1), MAX, image -> { throw stop; })).isSameAs(stop);
        verify(client, never()).getGenerationImage(7, 1);
    }

    @Test
    void contractViolationsAreTerminalImageChanges() {
        for (feign.Response response : List.of(
            image(200, new byte[] {1, 2}, null, "application/octet-stream"),
            image(200, new byte[] {1, 2}, "0", "application/octet-stream"),
            image(200, new byte[] {1, 2}, Integer.toString(MAX + 1), "application/octet-stream"),
            image(200, new byte[] {1, 2}, "2", "image/png"),
            image(200, new byte[] {1, 2}, "2", null),
            image(200, new byte[] {1, 2}, "3", "application/octet-stream"),
            image(200, new byte[] {1, 2, 3}, "2", "application/octet-stream"))) {
            when(client.getGenerationImage(7, 0)).thenReturn(response);
            failure("SIZE_CHART_IMAGE_CHANGED", false);
        }
    }

    @Test
    void productImageChangedIsTerminalAndOtherClientErrorsAreRejections() {
        when(client.getGenerationImage(7, 0)).thenReturn(error(410, "SIZE_CHART_DRAFT_016"));
        failure("SIZE_CHART_IMAGE_CHANGED", false);
        when(client.getGenerationImage(7, 0)).thenReturn(error(410, "SIZE_CHART_DRAFT_005"));
        failure("SIZE_CHART_INPUT_REJECTED", false);
        when(client.getGenerationImage(7, 0)).thenReturn(error(400, "SIZE_CHART_DRAFT_011"));
        failure("SIZE_CHART_INPUT_REJECTED", false);
        when(client.getGenerationImage(7, 0)).thenReturn(error(503, "SIZE_CHART_DRAFT_504"));
        failure("SIZE_CHART_INPUT_UNAVAILABLE", true);
    }

    @Test
    void cutOffBodyIsRetryableWithoutCause() {
        var body = mock(feign.Response.Body.class);
        try {
            when(body.asInputStream()).thenReturn(new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("person@example.invalid");
                }
            });
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
        when(client.getGenerationImage(7, 0)).thenReturn(feign.Response.builder().status(200).request(request())
            .headers(headers("2", "application/octet-stream")).body(body).build());
        failure("SIZE_CHART_INPUT_UNAVAILABLE", true);
    }

    @Test
    void generationInputCarriesImageMetadataAndRejectsInconsistentCounts() {
        var images = List.of(new SizeChartGenerationInputInternalResponse.Image(0, "a".repeat(64), "image/png"));
        when(client.getGenerationInput(7)).thenReturn(Response.success(
            new SizeChartGenerationInputInternalResponse("7", "b".repeat(64), "{}", "{}", "IMAGE", 1, images)));
        var input = adapter.getGenerationInput(7);
        assertThat(input.mode()).isEqualTo("IMAGE");
        assertThat(input.images()).singleElement().satisfies(image -> {
            assertThat(image.index()).isZero();
            assertThat(image.contentType()).isEqualTo("image/png");
        });

        when(client.getGenerationInput(7)).thenReturn(Response.success(
            new SizeChartGenerationInputInternalResponse("7", "b".repeat(64), "{}", "{}", "IMAGE", 2, images)));
        assertThatThrownBy(() -> adapter.getGenerationInput(7)).isInstanceOfSatisfying(SizeChartGenerationRemoteFailure.class,
            failure -> assertThat(failure.getErrorCode()).isEqualTo("SIZE_CHART_INPUT_INVALID"));
        when(client.getGenerationInput(7)).thenReturn(Response.success(new SizeChartGenerationInputInternalResponse("7", "b".repeat(64), "{}",
            "{}", "IMAGE", 1, List.of(new SizeChartGenerationInputInternalResponse.Image(1, "a".repeat(64), "image/png")))));
        assertThatThrownBy(() -> adapter.getGenerationInput(7)).isInstanceOfSatisfying(SizeChartGenerationRemoteFailure.class,
            failure -> assertThat(failure.getErrorCode()).isEqualTo("SIZE_CHART_INPUT_INVALID"));
    }

    private void failure(String code, boolean retryable) {
        assertThatThrownBy(() -> adapter.readGenerationImages(7, List.of(0), MAX, image -> { }))
            .isInstanceOfSatisfying(SizeChartGenerationRemoteFailure.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(code);
                assertThat(exception.isRetryable()).isEqualTo(retryable);
                assertThat(exception.getCause()).isNull();
                assertThat(exception.toString()).doesNotContain("person@example.invalid");
            });
    }

    private static feign.Response image(int status, byte[] bytes, String length, String type) {
        return feign.Response.builder().status(status).request(request()).headers(headers(length, type))
            .body(new ByteArrayInputStream(bytes), bytes.length).build();
    }

    private static feign.Response error(int status, String code) {
        byte[] body = ("{\"dataHeader\":{\"success\":false,\"resultCode\":\"" + code + "\",\"resultMessage\":\"person@example.invalid\"}}")
            .getBytes(StandardCharsets.UTF_8);
        return feign.Response.builder().status(status).reason("private").request(request())
            .headers(headers(Integer.toString(body.length), "application/json")).body(body).build();
    }

    private static Map<String, Collection<String>> headers(String length, String type) {
        Map<String, Collection<String>> headers = new java.util.HashMap<>();
        if (length != null) {
            headers.put("Content-Length", List.of(length));
        }
        if (type != null) {
            headers.put("Content-Type", List.of(type));
        }
        return headers;
    }

    private static Request request() {
        return Request.create(Request.HttpMethod.GET, "http://product-service/internal/v1/size-chart-drafts/7/generation-images/0",
            Map.of(), null, StandardCharsets.UTF_8, null);
    }
}
