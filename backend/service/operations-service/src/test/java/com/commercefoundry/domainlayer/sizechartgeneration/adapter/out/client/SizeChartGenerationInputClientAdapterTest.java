package com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.feign.SizeChartGenerationInputClient;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.feign.dto.SizeChartGenerationInputInternalResponse;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.support.SizeChartGenerationInputInternalResponseSupport;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import feign.Response.Builder;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

class SizeChartGenerationInputClientAdapterTest {
    private final SizeChartGenerationInputClient client = mock(SizeChartGenerationInputClient.class);
    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
    private final SizeChartGenerationInputInternalResponseSupport support = new SizeChartGenerationInputInternalResponseSupport(registry);
    private final SizeChartGenerationInputClientAdapter adapter = new SizeChartGenerationInputClientAdapter(client, support);

    @BeforeEach
    void setUp() {
        when(client.getGenerationInput(7)).thenReturn(Response.success(body("7")));
    }

    @Test
    void usesServiceTokenConfigurationAndConvertsStringResponseId() throws Exception {
        var config = SizeChartGenerationInputClient.class.getAnnotation(FeignClient.class);
        assertThat(config.configuration()).containsExactly(InternalFeignClientConfiguration.class);
        assertThat(config.contextId()).isEqualTo("sizeChartGenerationInputClient");
        assertThat(SizeChartGenerationInputClient.class.getMethod("getGenerationInput", long.class)
            .getAnnotation(GetMapping.class).value()).containsExactly("/internal/v1/size-chart-drafts/{draftId}/generation-input");
        assertThat(adapter.getGenerationInput(7).draftId()).isEqualTo(7);
        assertThat(adapter.getGenerationInput(7).toString()).doesNotContain("person@example.invalid");
        assertThat(body("7").toString()).doesNotContain("person@example.invalid");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "-7", "07", "7.0", "person@example.invalid", "9223372036854775808", " 7"})
    void invalidStringIdIsSafeTerminalContractError(String draftId) {
        when(client.getGenerationInput(7)).thenReturn(Response.success(body(draftId)));
        failure("SIZE_CHART_INPUT_INVALID", false);
    }

    @Test
    void rejectsMissingAndFailureEnvelopes() {
        when(client.getGenerationInput(7)).thenReturn(null);
        failure("SIZE_CHART_INPUT_INVALID", false);
        when(client.getGenerationInput(7)).thenReturn(Response.fail("PRIVATE", "person@example.invalid"));
        failure("SIZE_CHART_INPUT_INVALID", false);
    }

    @Test
    void mapsHttpErrorsWithoutRemoteBodyOrCause() {
        for (int status : new int[] {400, 403, 404, 429, 500, 503}) {
            Request request = Request.create(Request.HttpMethod.GET, "http://product-service/internal/v1/size-chart-drafts/7/generation-input",
                Map.of(), null, StandardCharsets.UTF_8, null);
            Builder response = feign.Response.builder().status(status).reason("private").request(request)
                .body("person@example.invalid", StandardCharsets.UTF_8);
            doThrow(FeignException.errorStatus("generationInput", response.build())).when(client).getGenerationInput(7);
            failure(status < 500 ? "SIZE_CHART_INPUT_REJECTED" : "SIZE_CHART_INPUT_UNAVAILABLE", status >= 500);
        }
    }

    @Test
    void openCircuitIsRetryable() {
        registry.circuitBreaker("product-service").transitionToOpenState();
        failure("SIZE_CHART_INPUT_UNAVAILABLE", true);
    }

    @Test
    void transportFailureIsRetryableAndDropsCause() {
        Request request = Request.create(Request.HttpMethod.GET, "http://product-service/internal/v1/size-chart-drafts/7/generation-input",
            Map.of(), null, StandardCharsets.UTF_8, null);
        doThrow(new RetryableException(-1, "person@example.invalid", Request.HttpMethod.GET,
            new java.io.IOException("provider-secret"), (Long) null, request)).when(client).getGenerationInput(7);
        failure("SIZE_CHART_INPUT_UNAVAILABLE", true);
    }

    private SizeChartGenerationInputInternalResponse body(String id) {
        return new SizeChartGenerationInputInternalResponse(id, "a".repeat(64), "person@example.invalid", "{}");
    }

    private void failure(String code, boolean retryable) {
        assertThatThrownBy(() -> adapter.getGenerationInput(7)).isInstanceOfSatisfying(SizeChartGenerationRemoteFailure.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(code);
            assertThat(exception.isRetryable()).isEqualTo(retryable);
            assertThat(exception.getCause()).isNull();
            assertThat(exception.getMessage()).isNull();
            assertThat(exception.toString()).doesNotContain("person@example.invalid", "provider-secret");
        });
    }
}
