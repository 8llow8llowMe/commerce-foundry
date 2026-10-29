package com.commercefoundry.domainlayer.order.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.StockReservationInternalResponse;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException.Failure;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import com.commercefoundry.global.support.RemoteResultCodes;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Product circuit and contract conversion. Never log raw errors, response bodies or throwables. */
@Slf4j
@Component
public class StockReservationInternalResponseSupport {

    private static final Map<String, Failure> FAILURES = Map.of(
        "PRODUCT_021", Failure.INSUFFICIENT, "PRODUCT_022", Failure.NOT_PURCHASABLE,
        "PRODUCT_023", Failure.ITEMS_MISMATCH, "PRODUCT_026", Failure.CONFLICT);

    private final CircuitBreaker circuitBreaker;
    private final JsonMapper jsonMapper;

    public StockReservationInternalResponseSupport(CircuitBreakerRegistry registry, JsonMapper jsonMapper) {
        this.circuitBreaker = registry.circuitBreaker("product-service");
        this.jsonMapper = jsonMapper;
    }

    public Response<StockReservationInternalResponse> call(Supplier<Response<StockReservationInternalResponse>> remoteCall) {
        try {
            return circuitBreaker.executeSupplier(remoteCall);
        } catch (FeignException.FeignClientException exception) {
            // 4xx is ignored by the circuit configuration; translate outside the circuit.
            String code = resultCodeOf(exception);
            Failure failure = exception.status() == 409 && code != null ? FAILURES.get(code) : null;
            if (failure == null) {
                log.error("product-service rejected stock request. status={}, resultCode={}", exception.status(), RemoteResultCodes.loggable(code));
            }
            throw new StockReservationException(failure == null ? Failure.REJECTED : failure, exception);
        } catch (FeignException | CallNotPermittedException exception) {
            log.warn("product-service stock call failed. type={}", exception.getClass().getSimpleName());
            throw new StockReservationException(Failure.UNKNOWN, exception);
        }
    }

    /** Validate every item against the request. Processor uses its injected Clock to check expiry. */
    public StockReservationQueryResult toQueryResult(Response<StockReservationInternalResponse> response, long orderId, SortedMap<Long, Integer> requested) {
        try {
            if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
                throw new IllegalArgumentException("success envelope required");
            }
            StockReservationInternalResponse body = response.dataBody();
            long reservationId = positiveId(body.reservationId());
            long returnedOrderId = positiveId(body.orderId());
            if (returnedOrderId != orderId || !"RESERVED".equals(body.status()) || body.expiresAt() == null || body.items() == null) {
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
