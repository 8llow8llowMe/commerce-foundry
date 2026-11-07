package com.commercefoundry.domainlayer.sizechart.adapter.out.client;

import static com.commercefoundry.domainlayer.sizechart.SizeChartTransportFixtures.HASH;
import static com.commercefoundry.domainlayer.sizechart.SizeChartTransportFixtures.NOW;
import static com.commercefoundry.domainlayer.sizechart.SizeChartTransportFixtures.draft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.SizeChartGenerationClient;
import com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.SizeChartGenerationFeignConfiguration;
import com.commercefoundry.domainlayer.sizechart.adapter.out.client.support.SizeChartGenerationInternalResponseSupport;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import feign.Client;
import feign.Feign;
import feign.Request;
import feign.codec.DecodeException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import tools.jackson.databind.json.JsonMapper;

/** Real Feign contract/serialization with an in-memory HTTP Client; no discovery or network required. */
class SizeChartGenerationFeignTransportTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SizeChartGenerationFeignConfiguration config = new SizeChartGenerationFeignConfiguration();

    @Test
    void postContainsOnlyReceiptFieldsAndGetUsesBoundJobPath() {
        AtomicReference<Request> captured = new AtomicReference<>();
        var adapter = adapter((request, options) -> {
            captured.set(request);
            assertThat(options.connectTimeoutMillis()).isEqualTo(2000);
            assertThat(options.readTimeoutMillis()).isEqualTo(3000);
            assertThat(options.isFollowRedirects()).isFalse();
            if (request.httpMethod() == Request.HttpMethod.POST) {
                return response(request, "{\"draftId\":\"7\",\"jobId\":\"9\",\"inputHash\":\"" + HASH + "\"}");
            }
            return response(request, "{\"draftId\":\"7\",\"jobId\":\"9\",\"inputHash\":\"" + HASH
                + "\",\"status\":\"PENDING\",\"failureCode\":null,\"resultJson\":null}");
        });
        assertThat(adapter.register(draft()).jobId()).isEqualTo(9);
        assertThat(captured.get().httpMethod()).isEqualTo(Request.HttpMethod.POST);
        assertThat(captured.get().url()).isEqualTo("http://operations-service/internal/v1/size-chart-generation-jobs");
        var json = mapper.readTree(captured.get().body());
        assertThat(json.size()).isEqualTo(4);
        assertThat(json.get("draftId").longValue()).isEqualTo(7);
        assertThat(json.get("inputHash").stringValue()).isEqualTo(HASH);
        assertThat(json.get("requestedByMemberId").longValue()).isEqualTo(100);
        assertThat(mapper.treeToValue(json.get("expiresAt"), java.time.Instant.class)).isEqualTo(draft().expiresAt());
        adapter.result(draft().bindJob(9, NOW));
        assertThat(captured.get().httpMethod()).isEqualTo(Request.HttpMethod.GET);
        assertThat(captured.get().url()).endsWith("/internal/v1/size-chart-generation-jobs/9/result");
    }

    @ParameterizedTest
    @ValueSource(strings = {"7", "true", "[]", "{}"})
    void numericOrOtherNonStringWireIdsAreSafeContractFailures(String token) {
        var adapter = adapter((request, options) -> response(request,
            "{\"draftId\":" + token + ",\"jobId\":\"9\",\"inputHash\":\"" + HASH + "\"}"));
        failure(() -> adapter.register(draft()), SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
    }

    @Test
    void socketTimeoutIsUnavailableAndNeverRetriedForEitherOperation() {
        AtomicInteger calls = new AtomicInteger();
        var adapter = adapter((request, options) -> {
            calls.incrementAndGet();
            throw new SocketTimeoutException("private-source-marker");
        });
        failure(() -> adapter.register(draft()), SizeChartDraftTransportErrorCode.REGISTRATION_UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(1);
        failure(() -> adapter.result(draft().bindJob(9, NOW)), SizeChartDraftTransportErrorCode.RESULT_UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void feignUsesServiceTokenConfigurationAndBodyLoggingIsOff() {
        var annotation = SizeChartGenerationClient.class.getAnnotation(FeignClient.class);
        assertThat(annotation.configuration()).contains(InternalFeignClientConfiguration.class, SizeChartGenerationFeignConfiguration.class);
        assertThat(annotation.contextId()).isEqualTo("sizeChartGenerationClient");
        assertThat(config.sizeChartGenerationLoggerLevel()).isEqualTo(feign.Logger.Level.NONE);
    }

    private SizeChartGenerationClientAdapter adapter(Client transport) {
        var client = Feign.builder().contract(new SpringMvcContract()).client(transport)
            .options(config.sizeChartGenerationOptions()).retryer(config.sizeChartGenerationRetryer())
            .encoder((object, type, template) -> template.body(mapper.writeValueAsBytes(object), StandardCharsets.UTF_8))
            .decoder((response, type) -> {
                try {
                    return mapper.readValue(response.body().asInputStream(), mapper.getTypeFactory().constructType(type));
                } catch (RuntimeException exception) {
                    throw new DecodeException(response.status(), "Invalid internal response", response.request());
                }
            }).target(SizeChartGenerationClient.class, "http://operations-service");
        return new SizeChartGenerationClientAdapter(client,
            new SizeChartGenerationInternalResponseSupport(CircuitBreakerRegistry.ofDefaults(), mapper));
    }

    private feign.Response response(Request request, String body) {
        return feign.Response.builder().request(request).status(200).reason("OK")
            .body("{\"dataHeader\":{\"success\":true},\"dataBody\":" + body + "}", StandardCharsets.UTF_8).build();
    }

    private void failure(Runnable call, SizeChartDraftTransportErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SizeChartDraftTransportException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(code);
            assertThat(exception.getCause()).isNull();
            assertThat(exception.toString()).doesNotContain("private-source-marker");
        });
    }
}
