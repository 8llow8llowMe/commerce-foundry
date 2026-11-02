package com.commercefoundry.domainlayer.order.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.PaymentInternalResponse;
import com.commercefoundry.domainlayer.order.application.exception.PaymentQueryException;
import com.commercefoundry.domainlayer.order.application.exception.PaymentQueryException.Failure;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentQueryResult;
import com.commercefoundry.global.support.RemoteResultCodes;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * payment-service internal 결제 조회의 서킷 · 예외 변환 · 응답 계약 확인 (#308, {@code coding-conventions.md} §10). {@code FeignException} 과 원격 응답 모양을 이 밖으로 내보내지 않는다.
 *
 * <ul>
 *   <li>서킷 인스턴스는 {@value #CIRCUIT_BREAKER} 다. 설정은 {@code application.yml} 의 {@code resilience4j.circuitbreaker.instances.payment-service} — 4xx 는 세지 않는다.</li>
 *   <li>404 {@code PAYMENT_001} — 없는 결제, 빈 결과다(예외 아님). 부른 쪽이 주문을 바꾸지 않고 DLT 로 보낸다.</li>
 *   <li>서킷 오픈 · 전송 실패 · 타임아웃 · 5xx — {@link Failure#UNAVAILABLE}(재시도 대상).</li>
 *   <li>401 {@code SECURITY_007} · 403 — {@link Failure#UNAUTHORIZED}(재시도 대상), error 로그. 서비스 토큰 키 교체 · 허용 호출자 설정 실수 시간대의
 *       승인 이벤트가 바로 DLT 로 가 "돈은 받았는데 주문 만료" 가 되지 않게 한다 — 재고 쪽 "모르는 4xx → 503 재시도" 와 같은 방향이다.</li>
 *   <li>그 밖의 4xx(400 · 코드 없는 404 등) — {@link Failure#REJECTED}(재시도해도 같다), error 로그.</li>
 *   <li>성공 응답이 계약과 다름(다른 결제 아이디 · 아이디 형식 · 금액 0 이하 · 모르는 통화 · 상태 없음 · DONE 인데 승인 시각 없음 · 본문 없는 200) — {@link Failure#CONTRACT_VIOLATION}, error 로그.</li>
 *   <li>로그에는 status · resultCode(코드 모양일 때만, {@link RemoteResultCodes}) · 예외 타입 · 결제 아이디만 남긴다. 응답 본문은 남기지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class PaymentInternalResponseSupport {

    static final String CIRCUIT_BREAKER = "payment-service";
    private static final String NOT_FOUND_CODE = "PAYMENT_001";

    private final CircuitBreaker circuitBreaker;
    private final JsonMapper jsonMapper;

    public PaymentInternalResponseSupport(CircuitBreakerRegistry circuitBreakerRegistry, JsonMapper jsonMapper) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
        this.jsonMapper = jsonMapper;
    }

    /**
     * 원격 호출을 서킷으로 감싸고 응답을 결과로 바꾼다.
     *
     * @param paymentId 요청한 결제 아이디 — 응답 대조용
     * @return 결제. 없는 결제(404 {@code PAYMENT_001})면 빈 값
     * @throws PaymentQueryException 위 사유
     */
    public Optional<PaymentQueryResult> find(long paymentId, Supplier<Response<PaymentInternalResponse>> remoteCall) {
        Response<PaymentInternalResponse> response;
        try {
            response = circuitBreaker.executeSupplier(remoteCall);
        } catch (CallNotPermittedException exception) {
            log.warn("payment-service circuit open. circuitBreaker={}", CIRCUIT_BREAKER);
            throw new PaymentQueryException(Failure.UNAVAILABLE, exception);
        } catch (FeignException.FeignClientException exception) {
            // 서킷은 이 예외를 세지 않고(ignore-exceptions) 지나보냈다. resultCode 해석은 서킷 밖에서 한다
            String resultCode = resultCodeOf(exception);
            if (exception.status() == 404 && NOT_FOUND_CODE.equals(resultCode)) {
                return Optional.empty();
            }
            log.error("payment-service rejected internal request. status={}, resultCode={}", exception.status(), RemoteResultCodes.loggable(resultCode));
            boolean authorization = exception.status() == 401 || exception.status() == 403;
            throw new PaymentQueryException(authorization ? Failure.UNAUTHORIZED : Failure.REJECTED, exception);
        } catch (FeignException exception) {
            log.warn("payment-service call failed. status={}, type={}", exception.status(), exception.getClass().getSimpleName());
            throw new PaymentQueryException(Failure.UNAVAILABLE, exception);
        }
        return Optional.of(toQueryResult(response, paymentId));
    }

    /** 응답의 결제 아이디가 요청과 다르거나 칸이 계약과 다르면 {@link Failure#CONTRACT_VIOLATION} 이다. */
    PaymentQueryResult toQueryResult(Response<PaymentInternalResponse> response, long paymentId) {
        try {
            if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
                throw new IllegalArgumentException("success envelope required");
            }
            PaymentInternalResponse body = response.dataBody();
            if (positiveId(body.paymentId()) != paymentId) {
                throw new IllegalArgumentException("payment id mismatch");
            }
            if (body.status() == null || body.amount() == null || body.amount().amount() <= 0) {
                throw new IllegalArgumentException("status and positive amount required");
            }
            Money amount = Money.of(body.amount().amount(), CurrencyCode.valueOf(body.amount().currency()));
            return new PaymentQueryResult(paymentId, positiveId(body.orderId()), positiveId(body.memberId()), body.status().code(), amount,
                body.approvedAt(), body.method() == null ? null : body.method().code());
        } catch (RuntimeException exception) {
            log.error("payment-service payment response violates contract. paymentId={}, type={}", paymentId, exception.getClass().getSimpleName());
            throw new PaymentQueryException(Failure.CONTRACT_VIOLATION, exception);
        }
    }

    private static long positiveId(String id) {
        long value = Long.parseLong(id);
        if (value <= 0) {
            throw new IllegalArgumentException("positive id required");
        }
        return value;
    }

    /** 오류 봉투의 {@code dataHeader.resultCode}. 본문이 없거나 JSON 이 아니거나 코드가 없으면 null 이다. */
    private String resultCodeOf(FeignException exception) {
        String body = exception.contentUTF8();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            ErrorEnvelope envelope = jsonMapper.readValue(body, ErrorEnvelope.class);
            return envelope == null || envelope.dataHeader() == null ? null : envelope.dataHeader().resultCode();
        } catch (JacksonException parseFailure) {
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
