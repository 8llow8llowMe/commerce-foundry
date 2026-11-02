package com.commercefoundry.domainlayer.order.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.StockReservationInternalResponse;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException.Failure;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockConfirmationQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import com.commercefoundry.global.support.RemoteResultCodes;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * product-service 재고 선점 · 확정의 서킷 · 사유 변환 · 응답 계약 확인. 원격 오류 원문 · 응답 본문 · 원인 예외는 로그에 남기지 않는다.
 *
 * <p>업무 거절은 호출마다 아는 (HTTP 상태, {@code resultCode}) 짝만 사유로 바꾼다 — 그 밖의 4xx 는 {@link Failure#REJECTED}, 전송 실패 · 타임아웃 · 5xx · 서킷 오픈은
 * {@link Failure#UNKNOWN} 이다. 선점에서 {@code PRODUCT_025} · {@code PRODUCT_003} 은 나오지 않으므로 {@code REJECTED} 그대로다.
 */
@Slf4j
@Component
public class StockReservationInternalResponseSupport {

    /** 선점(주문 생성 · 재선점)의 업무 거절 — 모두 409. */
    private static final Map<String, Failure> RESERVE_FAILURES = Map.of(
        "409:PRODUCT_021", Failure.INSUFFICIENT, "409:PRODUCT_022", Failure.NOT_PURCHASABLE,
        "409:PRODUCT_023", Failure.ITEMS_MISMATCH, "409:PRODUCT_026", Failure.CONFLICT);

    /** 확정의 업무 거절 (#308) — 해제 · 만료와 없음은 재선점 신호, 동시 변경은 다시 부를 일시 오류다 ({@code order-flow-guide.md} §3-3 표). */
    private static final Map<String, Failure> CONFIRM_FAILURES = Map.of(
        "409:PRODUCT_025", Failure.RELEASED, "404:PRODUCT_003", Failure.NOT_FOUND, "409:PRODUCT_026", Failure.CONFLICT);

    private final CircuitBreaker circuitBreaker;
    private final JsonMapper jsonMapper;

    public StockReservationInternalResponseSupport(CircuitBreakerRegistry registry, JsonMapper jsonMapper) {
        this.circuitBreaker = registry.circuitBreaker("product-service");
        this.jsonMapper = jsonMapper;
    }

    /** 선점 · 재선점 호출. */
    public Response<StockReservationInternalResponse> call(Supplier<Response<StockReservationInternalResponse>> remoteCall) {
        return call(remoteCall, RESERVE_FAILURES);
    }

    /** 확정 호출 (#308). */
    public Response<StockReservationInternalResponse> callConfirmation(Supplier<Response<StockReservationInternalResponse>> remoteCall) {
        return call(remoteCall, CONFIRM_FAILURES);
    }

    private Response<StockReservationInternalResponse> call(Supplier<Response<StockReservationInternalResponse>> remoteCall, Map<String, Failure> failures) {
        try {
            return circuitBreaker.executeSupplier(remoteCall);
        } catch (FeignException.FeignClientException exception) {
            // 4xx is ignored by the circuit configuration; translate outside the circuit.
            String code = resultCodeOf(exception);
            Failure failure = code == null ? null : failures.get(exception.status() + ":" + code);
            if (failure == null) {
                log.error("product-service rejected stock request. status={}, resultCode={}", exception.status(), RemoteResultCodes.loggable(code));
            }
            throw new StockReservationException(failure == null ? Failure.REJECTED : failure, exception);
        } catch (FeignException | CallNotPermittedException exception) {
            log.warn("product-service stock call failed. type={}", exception.getClass().getSimpleName());
            throw new StockReservationException(Failure.UNKNOWN, exception);
        }
    }

    /** 주문 생성의 선점 응답 — RESERVED 만 받는다. 만료 시각이 미래인지는 Processor 가 주입한 Clock 으로 본다. */
    public StockReservationQueryResult toQueryResult(Response<StockReservationInternalResponse> response, long orderId, SortedMap<Long, Integer> requested) {
        return toQueryResult(response, orderId, requested, Set.of("RESERVED"));
    }

    /** 재선점 응답 (#308) — 같은 주문의 이미 확정된 선점(CONFIRMED)도 받는다. 항목 대조는 같다. */
    public StockReservationQueryResult toReReservationQueryResult(
        Response<StockReservationInternalResponse> response, long orderId, SortedMap<Long, Integer> requested
    ) {
        return toQueryResult(response, orderId, requested, Set.of("RESERVED", "CONFIRMED"));
    }

    /** Validate every item against the request. */
    private StockReservationQueryResult toQueryResult(
        Response<StockReservationInternalResponse> response, long orderId, SortedMap<Long, Integer> requested, Set<String> acceptedStatuses
    ) {
        try {
            StockReservationInternalResponse body = successBody(response);
            long reservationId = positiveId(body.reservationId());
            long returnedOrderId = positiveId(body.orderId());
            if (returnedOrderId != orderId || !acceptedStatuses.contains(body.status()) || body.expiresAt() == null || body.items() == null) {
                throw new IllegalArgumentException("reservation contract mismatch");
            }
            SortedMap<Long, Integer> returned = new TreeMap<>();
            long previous = 0;
            for (StockReservationInternalResponse.Item item : body.items()) {
                long skuId = positiveId(item.skuId());
                if (skuId <= previous || item.quantity() == null || item.quantity() <= 0 || returned.put(skuId, item.quantity()) != null) {
                    throw new IllegalArgumentException("invalid reservation items");
                }
                previous = skuId;
            }
            if (returned.isEmpty() || !returned.equals(requested)) {
                throw new IllegalArgumentException("reservation items mismatch");
            }
            return new StockReservationQueryResult(reservationId, returnedOrderId, body.expiresAt());
        } catch (RuntimeException exception) {
            log.error("product-service stock response violates contract. type={}", exception.getClass().getSimpleName());
            throw new StockReservationException(Failure.UNKNOWN, exception);
        }
    }

    /**
     * 확정 응답 (#308) — 성공 봉투 · CONFIRMED · 요청한 선점 아이디여야 한다. 그 밖은 결과를 모르는 실패({@link Failure#UNKNOWN})다 — 확정은 멱등이라 다시 부르면 된다.
     * 주문 아이디 대조는 Processor 가 한다.
     */
    public StockConfirmationQueryResult toConfirmationQueryResult(Response<StockReservationInternalResponse> response, long reservationId) {
        try {
            StockReservationInternalResponse body = successBody(response);
            if (positiveId(body.reservationId()) != reservationId || !"CONFIRMED".equals(body.status())) {
                throw new IllegalArgumentException("confirmation contract mismatch");
            }
            return new StockConfirmationQueryResult(reservationId, positiveId(body.orderId()));
        } catch (RuntimeException exception) {
            log.error("product-service stock confirmation response violates contract. type={}", exception.getClass().getSimpleName());
            throw new StockReservationException(Failure.UNKNOWN, exception);
        }
    }

    private static StockReservationInternalResponse successBody(Response<StockReservationInternalResponse> response) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
            throw new IllegalArgumentException("success envelope required");
        }
        return response.dataBody();
    }

    private static long positiveId(String id) {
        long value = Long.parseLong(id);
        if (value <= 0) {
            throw new IllegalArgumentException("positive id required");
        }
        return value;
    }

    private String resultCodeOf(FeignException exception) {
        try {
            ErrorEnvelope envelope = jsonMapper.readValue(exception.contentUTF8(), ErrorEnvelope.class);
            return envelope == null || envelope.dataHeader() == null ? null : envelope.dataHeader().resultCode();
        } catch (JacksonException | IllegalArgumentException exceptionWithoutBody) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorEnvelope(ErrorHeader dataHeader) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorHeader(String resultCode) {
    }
}
