package com.commercefoundry.domainlayer.productdraft.adapter.out.client.support;

import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftGenerationResultQueryResult;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftGenerationResultQueryResult.FailureCode;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftGenerationResultQueryResult.Status;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftCandidate;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftGeneration;
import feign.FeignException;
import feign.Response;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** #728 의 결과 응답만 처리한다. 등록 장애와 결과 조회 장애의 서킷을 분리한다. */
@Component
public class ProductDraftGenerationResultResponseSupport {

    static final int MAX_RESULT_BYTES = 32 * 1024;
    // JSON 문자열 안에서는 ASCII 한 바이트가 최대 6바이트(유니코드 이스케이프)로 늘고 바깥에 봉투가 붙는다.
    static final int MAX_RESPONSE_BYTES = 6 * MAX_RESULT_BYTES + 4096;
    private static final int MAX_ERROR_BYTES = 4096;
    static final String CIRCUIT_BREAKER = "product-draft-generation-result-operations-service";
    static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final CircuitBreaker circuitBreaker;

    public ProductDraftGenerationResultResponseSupport(CircuitBreakerRegistry registry) {
        CircuitBreakerConfig config = CircuitBreakerConfig.from(registry.getDefaultConfig())
            .ignoreException(error -> error instanceof RemoteStatus remote && remote.status >= 400 && remote.status < 500).build();
        circuitBreaker = registry.circuitBreaker(CIRCUIT_BREAKER, config);
    }

    public ProductDraftGenerationResultQueryResult result(ProductDraft draft, Supplier<Response> request) {
        byte[] body;
        try {
            body = circuitBreaker.executeSupplier(() -> receive(request));
        } catch (RemoteStatus error) {
            if (error.status == 410 && "PRODUCT_DRAFT_GENERATION_010".equals(error.code)) {
                return outcome(draft, Status.EXPIRED, null, null);
            }
            if (error.status == 502 && "PRODUCT_DRAFT_GENERATION_503".equals(error.code)
                || error.status < 500 && error.status != 401 && error.status != 403 && error.status != 408 && error.status != 429) {
                throw invalid();
            }
            throw unavailable();
        } catch (FeignException | CallNotPermittedException exception) {
            throw unavailable();
        }

        try {
            JsonNode envelope = JSON.readTree(body);
            JsonNode header = envelope.path("dataHeader");
            JsonNode data = envelope.path("dataBody");
            require(header.path("success").isBoolean() && header.path("success").booleanValue()
                && header.path("resultCode").isNull() && data.isObject());
            verifyBinding(draft, data);
            Status status = Status.valueOf(data.path("status").stringValue(""));
            JsonNode failure = data.path("failureCode");
            JsonNode result = data.path("resultJson");
            return switch (status) {
                case SUCCEEDED -> {
                    require(failure.isNull() && result.isString());
                    yield outcome(draft, status, null, ProductDraftGenerationCandidateReader.read(draft, result.stringValue()));
                }
                case FAILED -> {
                    require(failure.isString() && result.isNull());
                    yield outcome(draft, status, FailureCode.valueOf(failure.stringValue()), null);
                }
                case PENDING, CANCELLED -> {
                    require(failure.isNull() && result.isNull());
                    yield outcome(draft, status, null, null);
                }
                case EXPIRED -> throw invalid();
            };
        } catch (JacksonException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static void verifyBinding(ProductDraft draft, JsonNode data) {
        ProductDraftGeneration generation = draft.generation();
        JsonNode generationNo = data.path("generationNo");
        require(data.size() == 7 && generationNo.isIntegralNumber() && generationNo.canConvertToInt()
            && generationNo.intValue() == generation.generationNo()
            && Long.toString(draft.id()).equals(data.path("draftId").stringValue())
            && Long.toString(generation.jobId()).equals(data.path("jobId").stringValue())
            && generation.inputHash().equals(data.path("inputHash").stringValue()));
    }

    private static ProductDraftGenerationResultQueryResult outcome(ProductDraft draft, Status status, FailureCode failure,
        ProductDraftCandidate candidate) {
        ProductDraftGeneration generation = draft.generation();
        return new ProductDraftGenerationResultQueryResult(draft.id(), generation.generationNo(), generation.jobId(),
            generation.inputHash(), status, failure, candidate);
    }

    private static byte[] receive(Supplier<Response> request) {
        try (Response response = request.get()) {
            require(response != null);
            if (response.status() == 200) {
                byte[] body = boundedBody(response.body(), MAX_RESPONSE_BYTES);
                require(body.length > 0 && body.length <= MAX_RESPONSE_BYTES);
                return body;
            }
            String code = null;
            if (response.status() == 410 || response.status() == 502) {
                code = errorCode(boundedBody(response.body(), MAX_ERROR_BYTES));
            }
            throw new RemoteStatus(response.status(), code);
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    private static byte[] boundedBody(Response.Body body, int maxBytes) throws IOException {
        if (body == null) {
            return new byte[0];
        }
        try (InputStream stream = body.asInputStream()) {
            return stream.readNBytes(maxBytes + 1);
        }
    }

    private static String errorCode(byte[] body) {
        if (body.length == 0 || body.length > MAX_ERROR_BYTES) {
            return null;
        }
        try {
            JsonNode error = JSON.readTree(body);
            JsonNode header = error.path("dataHeader");
            JsonNode success = header.path("success");
            return success.isBoolean() && !success.booleanValue() && error.path("dataBody").isNull()
                ? header.path("resultCode").stringValue() : null;
        } catch (JacksonException exception) {
            return null;
        }
    }

    static void require(boolean valid) {
        if (!valid) {
            throw invalid();
        }
    }

    private static ProductDraftException invalid() {
        return new ProductDraftException(ProductDraftErrorCode.GENERATION_CONTRACT_VIOLATION);
    }

    private static ProductDraftException unavailable() {
        return new ProductDraftException(ProductDraftErrorCode.GENERATION_UNAVAILABLE);
    }

    private static final class RemoteStatus extends RuntimeException {

        private final int status;
        private final String code;

        private RemoteStatus(int status, String code) {
            super(null, null, false, false);
            this.status = status;
            this.code = code;
        }
    }
}
