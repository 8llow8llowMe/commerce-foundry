package com.commercefoundry.domainlayer.sizechart.adapter.out.client;

import static com.commercefoundry.domainlayer.sizechart.SizeChartTransportFixtures.HASH;
import static com.commercefoundry.domainlayer.sizechart.SizeChartTransportFixtures.NOW;
import static com.commercefoundry.domainlayer.sizechart.SizeChartTransportFixtures.draft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.SizeChartGenerationClient;
import com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.dto.SizeChartGenerationRegisterRequest;
import com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.dto.SizeChartGenerationRegistrationResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.dto.SizeChartGenerationResultResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.out.client.support.SizeChartGenerationInternalResponseSupport;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationStatus;
import feign.FeignException;
import feign.Request;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class SizeChartGenerationClientAdapterTest {
    private SizeChartGenerationClient client;
    private CircuitBreakerRegistry registry;
    private SizeChartGenerationClientAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(SizeChartGenerationClient.class);
        registry = CircuitBreakerRegistry.ofDefaults();
        adapter = new SizeChartGenerationClientAdapter(client, new SizeChartGenerationInternalResponseSupport(registry, JsonMapper.builder().build()));
    }

    @Test
    void registersOnlyStableIdentifiersAndReturnsApplicationValue() {
        when(client.register(any())).thenReturn(Response.success(new SizeChartGenerationRegistrationResponse("7", "9", HASH)));
        assertThat(adapter.register(draft()).jobId()).isEqualTo(9);
        assertThat(adapter.register(draft()).jobId()).isEqualTo(9);
        var captor = ArgumentCaptor.forClass(SizeChartGenerationRegisterRequest.class);
        verify(client, times(2)).register(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(request -> {
            assertThat(request.draftId()).isEqualTo(7);
            assertThat(request.inputHash()).isEqualTo(HASH);
            assertThat(request.requestedByMemberId()).isEqualTo(100);
            assertThat(request.expiresAt()).isEqualTo(draft().expiresAt());
        });
        assertThat(captor.getAllValues().getFirst()).isEqualTo(captor.getAllValues().getLast());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "-9", "09", "+9", "9.0", " 9", "9223372036854775808"})
    void rejectsMalformedRemoteJobId(String id) {
        when(client.register(any())).thenReturn(Response.success(new SizeChartGenerationRegistrationResponse("7", id, HASH)));
        registrationFailure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
    }

    @Test
    void rejectsWrongDraftHashAndExistingJobBinding() {
        for (var body : new SizeChartGenerationRegistrationResponse[] {
            new SizeChartGenerationRegistrationResponse("8", "9", HASH),
            new SizeChartGenerationRegistrationResponse("7", "9", "b".repeat(64)),
            new SizeChartGenerationRegistrationResponse("7", "9", null)
        }) {
            when(client.register(any())).thenReturn(Response.success(body));
            registrationFailure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        }
        when(client.register(any())).thenReturn(Response.success(new SizeChartGenerationRegistrationResponse("7", "10", HASH)));
        failure(() -> adapter.register(draft().bindJob(9, NOW)), SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
    }

    @Test
    void rejectsIncompleteAndFailedEnvelopesWithoutUsingRemoteMessage() {
        when(client.register(any())).thenReturn(null);
        registrationFailure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        when(client.register(any())).thenReturn(new Response<>(null, new SizeChartGenerationRegistrationResponse("7", "9", HASH)));
        registrationFailure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        when(client.register(any())).thenReturn(Response.success(null));
        registrationFailure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        when(client.register(any())).thenReturn(Response.fail("PRIVATE", "private-source-marker"));
        registrationFailure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
    }

    @Test
    void classifiesHttpErrorsWithoutBodyOrCauseAndIgnores4xxInCircuit() {
        for (int status : new int[] {400, 401, 403, 404, 408, 409, 429, 500, 503}) {
            doThrow(httpError(status)).when(client).register(any());
            var expected = status == 409 ? SizeChartDraftTransportErrorCode.INPUT_CONFLICT
                : status == 400 || status == 404 ? SizeChartDraftTransportErrorCode.INVALID_CONTRACT
                : SizeChartDraftTransportErrorCode.REGISTRATION_UNAVAILABLE;
            registrationFailure(expected);
        }
        assertThat(registry.circuitBreaker("operations-service").getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
    }

    @Test
    void openCircuitDoesNotInvokeClient() {
        registry.circuitBreaker("operations-service").transitionToOpenState();
        registrationFailure(SizeChartDraftTransportErrorCode.REGISTRATION_UNAVAILABLE);
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void readsAllSupportedStatusesAndSanitizedFailureCodes() {
        for (var status : SizeChartGenerationStatus.values()) {
            var body = result(status.name(), status == SizeChartGenerationStatus.FAILED ? "GENERATION_FAILED" : null,
                status == SizeChartGenerationStatus.SUCCEEDED ? "{}" : null);
            when(client.result(9)).thenReturn(Response.success(body));
            var result = adapter.result(draft().bindJob(9, NOW));
            assertThat(result.status()).isEqualTo(status);
            assertThat(result.jobId()).isEqualTo(9);
        }
        verify(client, times(4)).result(9);
    }

    @Test
    void rejectsInvalidStatusResultShapeSizeTrailingJsonAndFailureFields() {
        for (var body : new SizeChartGenerationResultResponse[] {
            result(null, null, null), result("RUNNING", null, null), result("SUCCEEDED", null, null),
            result("SUCCEEDED", null, "[]"), result("SUCCEEDED", null, "{} {}"), result("SUCCEEDED", null, "{broken"),
            result("SUCCEEDED", null, "{\"x\":\"" + "가".repeat(12000) + "\"}"),
            result("SUCCEEDED", "HANDLER_ERROR", "{}"), result("PENDING", null, "{}"),
            result("CANCELLED", "HANDLER_ERROR", null), result("FAILED", null, null), result("FAILED", "private-source-marker", null),
            new SizeChartGenerationResultResponse("8", "9", HASH, "PENDING", null, null),
            new SizeChartGenerationResultResponse("7", "10", HASH, "PENDING", null, null),
            new SizeChartGenerationResultResponse("7", "9", "b".repeat(64), "PENDING", null, null)
        }) {
            when(client.result(9)).thenReturn(Response.success(body));
            failure(() -> adapter.result(draft().bindJob(9, NOW)), SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        }
    }

    @Test
    void acceptsExactUtf8SizeLimitAndRedactsStringRepresentations() {
        String json = "{\"x\":\"" + "x".repeat(32760) + "\"}";
        assertThat(json.getBytes(StandardCharsets.UTF_8)).hasSize(32768);
        when(client.result(9)).thenReturn(Response.success(result("SUCCEEDED", null, json)));
        assertThat(adapter.result(draft().bindJob(9, NOW)).resultJson()).isEqualTo(json);
        assertThat(adapter.result(draft().bindJob(9, NOW)).toString()).doesNotContain(json);
        assertThat(result("SUCCEEDED", null, json).toString()).doesNotContain(json);
    }

    @Test
    void missingBindingDoesNotCallRemoteAndExpiredResultIsDistinct() {
        failure(() -> adapter.result(draft()), SizeChartDraftTransportErrorCode.BINDING_CONFLICT);
        org.mockito.Mockito.verifyNoInteractions(client);
        doThrow(httpError(410)).when(client).result(9);
        failure(() -> adapter.result(draft().bindJob(9, NOW)), SizeChartDraftTransportErrorCode.RESULT_EXPIRED);
        doThrow(httpError(503)).when(client).result(9);
        failure(() -> adapter.result(draft().bindJob(9, NOW)), SizeChartDraftTransportErrorCode.RESULT_UNAVAILABLE);
    }

    private SizeChartGenerationResultResponse result(String status, String failure, String json) {
        return new SizeChartGenerationResultResponse("7", "9", HASH, status, failure, json);
    }

    private FeignException httpError(int status) {
        Request request = Request.create(Request.HttpMethod.POST, "http://operations-service/internal/v1/size-chart-generation-jobs",
            Map.of(), null, StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("register", feign.Response.builder().request(request).status(status).reason("private-source-marker")
            .body("private-source-marker", StandardCharsets.UTF_8).build());
    }

    private void registrationFailure(SizeChartDraftTransportErrorCode expected) {
        failure(() -> adapter.register(draft()), expected);
    }

    private void failure(Runnable call, SizeChartDraftTransportErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SizeChartDraftTransportException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(expected);
            assertThat(exception.getCause()).isNull();
            assertThat(exception.toString()).doesNotContain("private-source-marker");
        });
    }
}
