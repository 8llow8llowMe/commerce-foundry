package com.commercefoundry.domainlayer.sizechart.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.dto.SizeChartGenerationRegistrationResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign.dto.SizeChartGenerationResultResponse;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationStatus;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartGenerationRegistrationQueryResult;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartGenerationResultQueryResult;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import feign.FeignException;
import feign.codec.DecodeException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Component
public class SizeChartGenerationInternalResponseSupport {
    private static final Set<String> FAILURE_CODES = Set.of("GENERATION_FAILED", "LEASE_EXPIRED", "RUN_TIMEOUT", "HANDLER_ERROR", "RESULT_TOO_LARGE");
    private static final int MAX_RESULT_BYTES = 32 * 1024;
    private final CircuitBreaker circuitBreaker;
    private final ObjectMapper mapper;

    public SizeChartGenerationInternalResponseSupport(CircuitBreakerRegistry registry, ObjectMapper mapper) {
        // This slice cannot edit application.yml. Preserve registry settings while excluding all HTTP 4xx.
        CircuitBreakerConfig config = CircuitBreakerConfig.from(registry.getDefaultConfig())
            .ignoreException(error -> error instanceof FeignException feign && feign.status() >= 400 && feign.status() < 500).build();
        circuitBreaker = registry.circuitBreaker("operations-service", config);
        this.mapper = mapper;
    }

    public <T> T call(Supplier<Response<T>> request, boolean registration) {
        try {
            return body(circuitBreaker.executeSupplier(request));
        } catch (DecodeException exception) {
            throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        } catch (FeignException exception) {
            int status = exception.status();
            if (status == 409) {
                throw failure(SizeChartDraftTransportErrorCode.INPUT_CONFLICT);
            }
            if (!registration && status == 410) {
                throw failure(SizeChartDraftTransportErrorCode.RESULT_EXPIRED);
            }
            if (status >= 400 && status < 500 && status != 401 && status != 403 && status != 408 && status != 429) {
                throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
            }
            throw unavailable(registration);
        } catch (CallNotPermittedException exception) {
            throw unavailable(registration);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        }
    }

    public SizeChartGenerationRegistrationQueryResult registration(SizeChartDraft draft, SizeChartGenerationRegistrationResponse body) {
        long jobId = binding(draft, body.draftId(), body.jobId(), body.inputHash());
        return new SizeChartGenerationRegistrationQueryResult(draft.id(), jobId, body.inputHash());
    }

    public SizeChartGenerationResultQueryResult result(SizeChartDraft draft, SizeChartGenerationResultResponse body) {
        long jobId = binding(draft, body.draftId(), body.jobId(), body.inputHash());
        SizeChartGenerationStatus status;
        try {
            status = SizeChartGenerationStatus.valueOf(body.status());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        }
        if (status == SizeChartGenerationStatus.SUCCEEDED) {
            validateJson(body.resultJson());
        } else if (body.resultJson() != null) {
            throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        }
        if (status == SizeChartGenerationStatus.FAILED) {
            if (body.failureCode() == null || !FAILURE_CODES.contains(body.failureCode())) {
                throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
            }
        } else if (body.failureCode() != null) {
            throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        }
        return new SizeChartGenerationResultQueryResult(draft.id(), jobId, body.inputHash(), status, body.failureCode(), body.resultJson());
    }

    private long binding(SizeChartDraft draft, String draftId, String remoteJobId, String hash) {
        long parsedDraftId = id(draftId);
        long jobId = id(remoteJobId);
        if (parsedDraftId != draft.id() || hash == null || !hash.matches("[0-9a-f]{64}") || !draft.input().inputHash().equals(hash)
            || (draft.jobId() != null && draft.jobId() != jobId)) {
            throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        }
        return jobId;
    }

    private long id(String id) {
        try {
            if (id == null || !id.matches("[1-9][0-9]{0,18}")) {
                throw new NumberFormatException();
            }
            return Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        }
    }

    private void validateJson(String json) {
        if (json == null || json.length() > MAX_RESULT_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        }
        try {
            var node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (node == null || !node.isObject()) {
                throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
            }
        } catch (JacksonException exception) {
            throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        }
    }

    private <T> T body(Response<T> response) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
            throw failure(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        }
        return response.dataBody();
    }

    private SizeChartDraftTransportException unavailable(boolean registration) {
        return failure(registration ? SizeChartDraftTransportErrorCode.REGISTRATION_UNAVAILABLE : SizeChartDraftTransportErrorCode.RESULT_UNAVAILABLE);
    }

    private SizeChartDraftTransportException failure(SizeChartDraftTransportErrorCode code) {
        return new SizeChartDraftTransportException(code);
    }
}
