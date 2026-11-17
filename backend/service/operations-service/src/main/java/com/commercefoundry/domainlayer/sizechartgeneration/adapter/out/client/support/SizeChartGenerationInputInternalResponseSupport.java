package com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure.Code;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.feign.dto.SizeChartGenerationInputInternalResponse;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationInputQueryResult;
import feign.FeignException;
import feign.codec.ErrorDecoder;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * product 사이즈표 생성 입력 호출의 서킷 · 예외 변환. 서킷은 product 호출 공용 {@code product-service} 다(4xx 는 {@code ignore-exceptions}).
 *
 * <ul>
 *   <li>4xx — 같은 초안으로는 다시 해도 같다(재시도 불가). 오류 봉투의 {@code resultCode} 가 바뀐 이미지({@code SIZE_CHART_DRAFT_016})면
 *       {@code SIZE_CHART_IMAGE_CHANGED}, 나머지는 거절이다.</li>
 *   <li>5xx · 전송 실패 · 타임아웃 · 본문 읽기 중 끊김 · 서킷 열림 — 다시 시도한다.</li>
 * </ul>
 * 원격 본문 · 원인 · 메시지는 실패에 싣지 않는다 — 봉투는 코드 비교에만 쓰고 버린다.
 *
 * <p>이미지 바이트는 원시 {@code feign.Response} 로 받아 ErrorDecoder 를 지나지 않는다. 그래서 {@link #requireSuccess} 가 서킷 안에서 2xx 가 아닌 응답을
 * 기본 ErrorDecoder 와 같은 예외로 바꿔(오류 본문은 {@link #MAX_ERROR_BODY_BYTES} 까지만 읽는다) 위 분류와 서킷 실패 집계가 JSON 엔드포인트와 같게 한다.
 */
@Component
public class SizeChartGenerationInputInternalResponseSupport {
    static final int MAX_ERROR_BODY_BYTES = 4096;
    private static final String IMAGE_CHANGED = "SIZE_CHART_DRAFT_016";
    private static final ErrorDecoder ERROR_DECODER = new ErrorDecoder.Default();
    private static final String IMAGE_METHOD_KEY = "SizeChartGenerationInputClient#getGenerationImage(long,int)";

    private final CircuitBreaker circuitBreaker;
    private final ObjectMapper mapper;

    public SizeChartGenerationInputInternalResponseSupport(CircuitBreakerRegistry registry, ObjectMapper mapper) {
        circuitBreaker = registry.circuitBreaker("product-service");
        this.mapper = mapper;
    }

    public <T> T call(Supplier<T> call) {
        try {
            return circuitBreaker.executeSupplier(call);
        } catch (FeignException.FeignClientException exception) {
            Code code = IMAGE_CHANGED.equals(resultCode(exception)) ? Code.SIZE_CHART_IMAGE_CHANGED : Code.SIZE_CHART_INPUT_REJECTED;
            throw new SizeChartGenerationRemoteFailure(code, false);
        } catch (FeignException | CallNotPermittedException | UncheckedIOException exception) {
            // No remote body, exception cause or diagnostic message can reach worker logs.
            // UncheckedIOException: the image body was cut off or the read timed out (counted as a circuit failure).
            throw new SizeChartGenerationRemoteFailure(Code.SIZE_CHART_INPUT_UNAVAILABLE, true);
        }
    }

    /** 원시 응답이 2xx 가 아니면 기본 ErrorDecoder 와 같은 {@code FeignException} 을 던진다. {@link #call} 의 공급자 안(서킷 안)에서 부른다. */
    public void requireSuccess(feign.Response response) {
        if (response.status() >= 200 && response.status() < 300) {
            return;
        }
        Exception decoded = ERROR_DECODER.decode(IMAGE_METHOD_KEY, response.toBuilder().body(boundedErrorBody(response)).build());
        if (decoded instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IllegalStateException("ErrorDecoder returned a checked exception");
    }

    public SizeChartGenerationInputQueryResult toQueryResult(Response<SizeChartGenerationInputInternalResponse> response) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
            throw invalid();
        }
        var body = response.dataBody();
        if (body.draftId() == null || !body.draftId().matches("[1-9][0-9]{0,18}")) {
            throw invalid();
        }
        long draftId;
        try {
            draftId = Long.parseLong(body.draftId());
        } catch (NumberFormatException exception) {
            throw invalid();
        }
        String mode = body.mode() == null ? "TEXT" : body.mode();
        List<SizeChartGenerationInputQueryResult.Image> images = new ArrayList<>();
        List<SizeChartGenerationInputInternalResponse.Image> received = body.images() == null ? List.of() : body.images();
        int count = body.imageCount() == null ? 0 : body.imageCount();
        if (count != received.size()) {
            throw invalid();
        }
        for (int position = 0; position < received.size(); position++) {
            var image = received.get(position);
            if (image == null || image.index() == null || image.index() != position) {
                throw invalid();
            }
            images.add(new SizeChartGenerationInputQueryResult.Image(position, image.sha256(), image.contentType()));
        }
        return new SizeChartGenerationInputQueryResult(draftId, body.inputHash(), body.sourceJson(), body.templateJson(), mode, images);
    }

    /** 오류 봉투의 {@code dataHeader.resultCode}. 읽을 수 없으면 null — 값을 로그로 내지 않는다. */
    private String resultCode(FeignException exception) {
        String body = exception.contentUTF8();
        if (body == null || body.isEmpty() || body.length() > MAX_ERROR_BODY_BYTES) {
            return null;
        }
        try {
            JsonNode code = mapper.readTree(body).path("dataHeader").path("resultCode");
            return code.isString() ? code.stringValue() : null;
        } catch (JacksonException ignored) {
            return null;
        }
    }

    private static byte[] boundedErrorBody(feign.Response response) {
        if (response.body() == null) {
            return new byte[0];
        }
        try (InputStream stream = response.body().asInputStream()) {
            byte[] body = stream.readNBytes(MAX_ERROR_BODY_BYTES + 1);
            return body.length > MAX_ERROR_BODY_BYTES ? new byte[0] : body;
        } catch (IOException exception) {
            return new byte[0];
        }
    }

    private static SizeChartGenerationRemoteFailure invalid() {
        return new SizeChartGenerationRemoteFailure(Code.SIZE_CHART_INPUT_INVALID, false);
    }
}
