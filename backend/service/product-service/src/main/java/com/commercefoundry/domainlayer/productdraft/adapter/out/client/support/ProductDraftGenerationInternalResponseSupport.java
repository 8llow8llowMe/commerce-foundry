package com.commercefoundry.domainlayer.productdraft.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.productdraft.adapter.out.client.feign.dto.ProductDraftGenerationRegistrationResponse;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftGenerationRegistrationQueryResult;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftGeneration;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import feign.FeignException;
import feign.codec.DecodeException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * operations 상품 초안 생성 등록 응답을 product 의 결과 · 업무 예외로 바꾼다 (#359 PR-B1, 선례: {@code SizeChartGenerationInternalResponseSupport}).
 * 원격 본문 · 메시지 · 파서 진단을 예외 · 로그에 싣지 않는다 — 오류 봉투에서 {@code resultCode} 만 읽는다.
 *
 * <p><b>서킷</b> — 상품 초안 등록 전용 인스턴스를 쓴다. 신규 등록 플래그가 닫힌 503 이 다른 operations 기능의 서킷을 열지 않게 한다.
 * 4xx 는 서킷 실패로 세지 않는다.
 *
 * <p><b>분류</b>
 * <ul>
 *   <li>409 {@code PRODUCT_DRAFT_GENERATION_002} — 같은 (초안, 회차)인데 hash · 요청자 · 만료가 다르다. product 가 저장 값만 보내므로 계약 위반이다 — error 로그, {@code 504}</li>
 *   <li>409 {@code PRODUCT_DRAFT_GENERATION_005} — 만료된 초안의 신규 등록 — {@code 022}</li>
 *   <li>5xx · 타임아웃 · 전송 실패 · 서킷 열림 · 401 · 403 · 408 · 429 — 등록 여부를 모른다 — {@code 502}(같은 값으로 다시 시도)</li>
 *   <li>그 밖의 4xx · 디코딩 실패 · 봉투 불량 · 초안 · 회차 · hash 불일치 — {@code 504}</li>
 * </ul>
 */
@Slf4j
@Component
public class ProductDraftGenerationInternalResponseSupport {

    static final String CIRCUIT_BREAKER = "product-draft-generation-operations-service";
    static final String INPUT_CONFLICT = "PRODUCT_DRAFT_GENERATION_002";
    static final String EXPIRED = "PRODUCT_DRAFT_GENERATION_005";
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CircuitBreaker circuitBreaker;

    public ProductDraftGenerationInternalResponseSupport(CircuitBreakerRegistry registry) {
        CircuitBreakerConfig config = CircuitBreakerConfig.from(registry.getDefaultConfig())
            .ignoreException(error -> error instanceof FeignException feign && feign.status() >= 400 && feign.status() < 500).build();
        this.circuitBreaker = registry.circuitBreaker(CIRCUIT_BREAKER, config);
    }

    /** 등록을 부르고 보낸 초안 회차와 맞는 결과만 돌려준다. */
    public ProductDraftGenerationRegistrationQueryResult register(ProductDraft draft, Supplier<Response<ProductDraftGenerationRegistrationResponse>> request) {
        Response<ProductDraftGenerationRegistrationResponse> response;
        try {
            response = circuitBreaker.executeSupplier(request);
        } catch (DecodeException exception) {
            throw contractViolation(draft, "decode");
        } catch (FeignException exception) {
            throw classify(draft, exception);
        } catch (CallNotPermittedException exception) {
            throw unavailable();
        } catch (JacksonException | IllegalArgumentException exception) {
            throw contractViolation(draft, "decode");
        }
        return registration(draft, response);
    }

    private ProductDraftGenerationRegistrationQueryResult registration(ProductDraft draft, Response<ProductDraftGenerationRegistrationResponse> response) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
            throw contractViolation(draft, "envelope");
        }
        ProductDraftGenerationRegistrationResponse body = response.dataBody();
        ProductDraftGeneration sent = draft.generation();
        if (!isId(body.draftId()) || !isId(body.jobId()) || body.generationNo() == null || body.inputHash() == null || !HASH.matcher(body.inputHash()).matches()) {
            throw contractViolation(draft, "format");
        }
        long draftId = Long.parseLong(body.draftId());
        if (draftId != draft.id() || body.generationNo() != sent.generationNo() || !body.inputHash().equals(sent.inputHash())) {
            throw contractViolation(draft, "binding");
        }
        return new ProductDraftGenerationRegistrationQueryResult(draftId, body.generationNo(), Long.parseLong(body.jobId()), body.inputHash());
    }

    private ProductDraftException classify(ProductDraft draft, FeignException exception) {
        int status = exception.status();
        if (status == 409) {
            String code = resultCodeOf(exception);
            if (EXPIRED.equals(code)) {
                return new ProductDraftException(ProductDraftErrorCode.DRAFT_EXPIRED);
            }
            if (INPUT_CONFLICT.equals(code)) {
                // product 는 저장한 회차 값만 보낸다 — 같은 회차의 입력 충돌은 저장 값이 바뀌었거나(정밀도 · 버그) operations 영수증이 어긋난 것이다.
                log.error("operations rejected product draft generation registration as input conflict (contract violation). draftId={}, generationNo={}",
                    draft.id(), draft.generation().generationNo());
                return new ProductDraftException(ProductDraftErrorCode.GENERATION_CONTRACT_VIOLATION);
            }
            return contractViolation(draft, "conflict");
        }
        if (status >= 400 && status < 500 && status != 401 && status != 403 && status != 408 && status != 429) {
            return contractViolation(draft, "status=" + status);
        }
        return unavailable();
    }

    private static boolean isId(String id) {
        return id != null && ID.matcher(id).matches() && isLong(id);
    }

    private static boolean isLong(String id) {
        try {
            Long.parseLong(id);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String resultCodeOf(FeignException exception) {
        try {
            ErrorEnvelope envelope = JSON.readValue(exception.contentUTF8(), ErrorEnvelope.class);
            return envelope == null || envelope.dataHeader() == null ? null : envelope.dataHeader().resultCode();
        } catch (JacksonException | IllegalArgumentException exceptionWithoutBody) {
            return null;
        }
    }

    private static ProductDraftException unavailable() {
        return new ProductDraftException(ProductDraftErrorCode.GENERATION_UNAVAILABLE);
    }

    private static ProductDraftException contractViolation(ProductDraft draft, String reason) {
        log.warn("operations product draft generation registration response is outside the contract. draftId={}, reason={}", draft.id(), reason);
        return new ProductDraftException(ProductDraftErrorCode.GENERATION_CONTRACT_VIOLATION);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorEnvelope(ErrorHeader dataHeader) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorHeader(String resultCode) {
    }
}
