package com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client.feign.dto.ProductDraftGenerationInputInternalResponse;
import com.commercefoundry.domainlayer.productdraftgeneration.application.model.ProductDraftGenerationRemoteFailure;
import com.commercefoundry.domainlayer.productdraftgeneration.application.model.ProductDraftGenerationRemoteFailure.Code;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationInputQueryResult;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * product 초안 생성 입력 호출의 서킷 · 예외 변환 (선례: {@code SizeChartGenerationInputInternalResponseSupport}). 서킷은 product 호출 공용 {@code product-service}
 * (yml {@code resilience4j.circuitbreaker.instances.product-service}, 4xx 는 {@code ignore-exceptions})다.
 *
 * <ul>
 *   <li>4xx — 같은 초안으로는 다시 해도 같다(재시도 불가). 오류 봉투의 {@code resultCode} 로 바뀐 이미지({@code PRODUCT_DRAFT_044}) · 만료({@code 022})만 따로 고르고
 *       나머지(없음 · 거절 · 상태 · 원본 미고정 · 기능 닫힘 · 순번 없음 · 인가)는 거절이다. 봉투가 깨졌어도 거절이다.</li>
 *   <li>5xx · 전송 실패 · 타임아웃 · 디코딩 실패 · 서킷 열림 — 다시 시도한다.</li>
 * </ul>
 * 원격 본문 · 원인 · 메시지는 실패에 싣지 않는다 — 봉투는 코드 비교에만 쓰고 버린다.
 */
@Component
public class ProductDraftGenerationInputInternalResponseSupport {

    private static final int MAX_ERROR_BODY_CHARS = 4096;

    private final CircuitBreaker circuitBreaker;
    private final ObjectMapper mapper;

    public ProductDraftGenerationInputInternalResponseSupport(CircuitBreakerRegistry registry, ObjectMapper mapper) {
        this.circuitBreaker = registry.circuitBreaker("product-service");
        this.mapper = mapper;
    }

    public <T> T call(Supplier<T> call) {
        try {
            return circuitBreaker.executeSupplier(call);
        } catch (FeignException.FeignClientException exception) {
            throw new ProductDraftGenerationRemoteFailure(classify(exception), false);
        } catch (FeignException | CallNotPermittedException exception) {
            throw new ProductDraftGenerationRemoteFailure(Code.PRODUCT_DRAFT_INPUT_UNAVAILABLE, true);
        }
    }

    public ProductDraftGenerationInputQueryResult toQueryResult(Response<ProductDraftGenerationInputInternalResponse> response) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
            throw invalid();
        }
        var body = response.dataBody();
        if (body.draftId() == null || !body.draftId().matches("[1-9][0-9]{0,18}") || body.facts() == null || body.imageCount() == null || body.images() == null) {
            throw invalid();
        }
        try {
            long draftId = Long.parseLong(body.draftId());
            var facts = body.facts();
            var images = body.images().stream().map(image -> {
                if (image == null || image.index() == null || image.byteLength() == null) {
                    throw new IllegalArgumentException();
                }
                return new ProductDraftGenerationInputQueryResult.Image(image.index(), image.imageType(), image.contentType(), image.byteLength(),
                    image.width(), image.height());
            }).toList();
            return new ProductDraftGenerationInputQueryResult(draftId, body.status(), body.expiresAt(),
                new ProductDraftGenerationInputQueryResult.Facts(facts.nameHint(), facts.materials(), facts.careNotes(), facts.measurementsNote(), facts.origin(),
                    facts.certifications(), facts.tone(), facts.mustInclude(), facts.mustAvoid()),
                body.imageCount(), images);
        } catch (NullPointerException | IllegalArgumentException exception) {
            // 숫자 형식(NumberFormatException) · List.copyOf 의 null 원소(NPE) — 계약 밖 본문이다
            throw invalid();
        }
    }

    private Code classify(FeignException.FeignClientException exception) {
        String resultCode = resultCode(exception);
        if ("PRODUCT_DRAFT_044".equals(resultCode)) {
            return Code.PRODUCT_DRAFT_IMAGE_CHANGED;
        }
        if ("PRODUCT_DRAFT_022".equals(resultCode)) {
            return Code.PRODUCT_DRAFT_INPUT_EXPIRED;
        }
        return Code.PRODUCT_DRAFT_INPUT_REJECTED;
    }

    /** 오류 봉투의 {@code dataHeader.resultCode}. 읽을 수 없으면 null — 값을 로그로 내지 않는다. */
    private String resultCode(FeignException exception) {
        String body = exception.contentUTF8();
        if (body == null || body.isEmpty() || body.length() > MAX_ERROR_BODY_CHARS) {
            return null;
        }
        try {
            JsonNode code = mapper.readTree(body).path("dataHeader").path("resultCode");
            return code.isString() ? code.stringValue() : null;
        } catch (JacksonException ignored) {
            return null;
        }
    }

    private static ProductDraftGenerationRemoteFailure invalid() {
        return new ProductDraftGenerationRemoteFailure(Code.PRODUCT_DRAFT_INPUT_INVALID, false);
    }
}
