package com.commercefoundry.domainlayer.payment.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.payment.adapter.out.client.feign.dto.OrderPaymentResultInternalResponse;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentResultQueryException;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentResultQueryException.Failure;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult.Result;
import com.commercefoundry.global.support.RemoteResultCodes;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import feign.FeignException;
import feign.RetryableException;
import feign.codec.DecodeException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 조회 전용 경계. 원격 계약만 검증하며 로컬 결제 · 이벤트 대조 및 상태 변경은 하지 않는다. */
@Slf4j
@Component
public class OrderPaymentResultResponseSupport {

    private final CircuitBreaker circuitBreaker;
    private final JsonMapper jsonMapper;

    public OrderPaymentResultResponseSupport(CircuitBreakerRegistry registry, JsonMapper jsonMapper) {
        this.circuitBreaker = registry.circuitBreaker("order-service");
        this.jsonMapper = jsonMapper;
    }

    public Response<OrderPaymentResultInternalResponse> call(Supplier<Response<OrderPaymentResultInternalResponse>> remoteCall) {
        try {
            return circuitBreaker.executeSupplier(() -> callWithoutRetryableClientFailure(remoteCall));
        } catch (CallNotPermittedException exception) {
            log.warn("order payment result query unavailable. type=CircuitOpen");
            throw failure(Failure.UNAVAILABLE);
        } catch (FeignException.FeignClientException exception) {
            // 4xx 는 기존 order-service 서킷 ignore-exceptions 설정으로 제외한다. 본문 해석은 서킷 밖이다.
            throw rejection(exception);
        } catch (DecodeException exception) {
            log.error("order payment result query violates contract. type=Decode");
            throw failure(Failure.CONTRACT_VIOLATION);
        } catch (FeignException exception) {
            // Retry-After 가 있으면 Feign 은 4xx 도 RetryableException 으로 감싼다. 재시도 없이 HTTP 상태로 분류한다.
            if (exception.status() >= 400 && exception.status() < 500) {
                throw rejection(exception);
            }
            log.warn("order payment result query unavailable. status={}", exception.status());
            throw failure(Failure.UNAVAILABLE);
        }
    }

    private static <T> T callWithoutRetryableClientFailure(Supplier<T> remoteCall) {
        try {
            return remoteCall.get();
        } catch (RetryableException exception) {
            // Retry-After 4xx 도 기존 ignore-exceptions 에 해당하게 서킷 내부에서 정규화한다.
            // 본문은 밖에서 결과 코드를 읽는 동안만 보존하며 원인 예외 · 원격 메시지를 옮기지 않는다.
            if (exception.status() >= 400 && exception.status() < 500) {
                // Feign 기본 ErrorDecoder 는 원본 응답을 cause 에 보관하고 RetryableException 의 본문은 비울 수 있다.
                // 같은 HTTP 상태의 직접 원본에서만 계약 판정에 필요한 본문 · 헤더를 복원한다.
                FeignException response = exception.getCause() instanceof FeignException original && original.status() == exception.status()
                    ? original : exception;
                throw new FeignException.FeignClientException(exception.status(), "Internal query rejected",
                    exception.request(), response.content(), response.responseHeaders());
            }
            throw exception;
        }
    }

    public OrderPaymentResultQueryResult toResult(Response<OrderPaymentResultInternalResponse> response, long paymentId) {
        try {
            if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
                throw new IllegalArgumentException();
            }
            OrderPaymentResultInternalResponse body = response.dataBody();
            long receivedPaymentId = parseId(body.paymentId());
            long orderId = parseId(body.orderId());
            long memberId = parseId(body.memberId());
            if (receivedPaymentId != paymentId || body.amount() == null || body.amount().amount() == null || body.amount().amount() <= 0) {
                throw new IllegalArgumentException();
            }
            Money amount = Money.of(body.amount().amount(), CurrencyCode.valueOf(body.amount().currency()));
            Result result = Result.valueOf(body.result());
            Long reservationId = body.stockReservationId() == null ? null : parseId(body.stockReservationId());
            if (result == Result.PAID && (body.approvedAt() == null || reservationId == null)) {
                throw new IllegalArgumentException();
            }
            if (result != Result.PAID && result != Result.REJECTED_ORDER_CANCELED && reservationId != null) {
                throw new IllegalArgumentException();
            }
            return new OrderPaymentResultQueryResult(paymentId, orderId, memberId, result, amount, body.approvedAt(), reservationId);
        } catch (RuntimeException exception) {
            log.error("order payment result query violates contract. type=Fields");
            throw failure(Failure.CONTRACT_VIOLATION);
        }
    }

    private static long parseId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException();
        }
        return Long.parseLong(value);
    }

    private static OrderPaymentResultQueryException failure(Failure failure) {
        // Feign 메시지 · cause 에 원격 본문이 들어 있으므로 전파하지 않는다.
        return new OrderPaymentResultQueryException(failure);
    }

    private OrderPaymentResultQueryException rejection(FeignException exception) {
        String code = resultCodeOf(exception);
        log.warn("order payment result query rejected. status={}, resultCode={}", exception.status(), RemoteResultCodes.loggable(code));
        if (exception.status() == 404 && "ORDER_028".equals(code)) {
            return failure(Failure.NOT_READY);
        }
        return failure(exception.status() == 401 || exception.status() == 403 ? Failure.UNAUTHORIZED : Failure.REJECTED);
    }

    private String resultCodeOf(FeignException exception) {
        try {
            ErrorEnvelope envelope = jsonMapper.readValue(exception.contentUTF8(), ErrorEnvelope.class);
            return envelope == null || envelope.dataHeader() == null ? null : envelope.dataHeader().resultCode();
        } catch (JacksonException | IllegalArgumentException exceptionIgnored) {
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
