package com.commercefoundry.domainlayer.payment.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.payment.adapter.out.client.feign.dto.OrderPaymentSummaryInternalResponse;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentSummaryQueryResult;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.global.support.RemoteResultCodes;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import feign.FeignException;
import feign.codec.DecodeException;
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
 * order-service internal 호출의 서킷 · 예외 변환 · 응답 변환 ({@code coding-conventions.md} §10). {@code FeignException} 과 원격 응답 모양을 이 밖으로 내보내지 않는다.
 *
 * <table>
 *   <caption>결과 분류</caption>
 *   <tr><th>order-service 응답</th><th>결과</th></tr>
 *   <tr><td>200 성공 봉투 + 계약을 지킨 본문</td><td>주문 요약</td></tr>
 *   <tr><td>404 {@code ORDER_001}(주문 없음 · 소유 아님)</td><td>비어 있음 — Processor 가 {@code PAYMENT_002}(404)로 바꾼다</td></tr>
 *   <tr><td>그 밖의 4xx(다른 404 · 401 {@code SECURITY_007} · 403 · 본문 검증 등) · 본문을 읽지 못한 4xx</td><td>error 로그 + {@code PAYMENT_011}(503)</td></tr>
 *   <tr><td>200 인데 계약 위반(실패 봉투 · 본문 없음 · 아이디 불일치 · 금액 0 이하 · 모르는 통화 · 주문명 비었거나 100자 초과 · 결제 기한 없음) · 역직렬화 실패</td>
 *       <td>error 로그 + {@code PAYMENT_011}</td></tr>
 *   <tr><td>서킷 오픈 · 5xx · 타임아웃 · 연결 실패</td><td>warn 로그 + {@code PAYMENT_011}</td></tr>
 * </table>
 *
 * <ul>
 *   <li>서킷 인스턴스는 {@value #CIRCUIT_BREAKER}(대상 서비스 논리명) — {@code application.yml} 의 {@code resilience4j.circuitbreaker.instances.order-service}.
 *       4xx({@code FeignClientException})는 그 인스턴스의 {@code ignore-exceptions} 로 서킷 실패로 세지 않는다. 본문의 {@code resultCode} 는 <b>서킷 밖</b>(catch)에서 읽는다.</li>
 *   <li>401 {@code SECURITY_007} 은 서비스 토큰 비밀 · 서비스 ID 설정 문제라 재시도로 풀리지 않는다 ({@code security-guide.md} §9-5) — 다시 발급해 재시도하지 않는다.</li>
 *   <li>로그에는 status · resultCode · 예외 타입만 남긴다. resultCode 는 코드 모양일 때만 그대로다({@link RemoteResultCodes}). 응답 본문(주문명 등)은 남기지 않는다.
 *       원인 예외({@code FeignException})의 메시지에는 대상 응답 본문이 들어 있으므로, 이 클래스가 던진 {@link PaymentException} 을 받는 핸들러는 원인을 throwable 로 로그하지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class OrderInternalResponseSupport {

    static final String CIRCUIT_BREAKER = "order-service";
    /** order-service 의 "주문 없음 · 소유 아님" 코드. 이 코드의 404 만 "주문 없음" 으로 읽는다 — 매핑 없는 경로(404 {@code COMMON_001})는 배포 불일치라 503 이다. */
    static final String ORDER_NOT_FOUND_CODE = "ORDER_001";
    private static final int NOT_FOUND = 404;

    private final CircuitBreaker circuitBreaker;
    private final JsonMapper jsonMapper;

    /** {@code jsonMapper} 는 서비스 컨텍스트의 빈(Boot 의 Jackson 3 자동설정)이다 — 오류 봉투 하나만 읽는다. */
    public OrderInternalResponseSupport(CircuitBreakerRegistry circuitBreakerRegistry, JsonMapper jsonMapper) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
        this.jsonMapper = jsonMapper;
    }

    /**
     * 원격 호출을 서킷으로 감싸고 실패를 {@link PaymentException} 으로 바꾼다.
     *
     * @return 404 {@value #ORDER_NOT_FOUND_CODE} 면 비어 있다. 2xx 인데 본문이 없으면 비어 있지 않고 계약 위반(503)이다 — "주문 없음" 으로 읽지 않는다
     */
    public <T> Optional<T> callAllowingOrderNotFound(Supplier<T> remoteCall) {
        T response;
        try {
            response = circuitBreaker.executeSupplier(remoteCall);
        } catch (CallNotPermittedException exception) {
            log.warn("order-service circuit open. circuitBreaker={}", CIRCUIT_BREAKER);
            throw unavailable(exception);
        } catch (FeignException.FeignClientException exception) {
            // 서킷은 이 예외를 세지 않고(ignore-exceptions) 지나보냈다. resultCode 해석은 여기 — 서킷 밖에서 한다
            String resultCode = resultCodeOf(exception);
            if (exception.status() == NOT_FOUND && ORDER_NOT_FOUND_CODE.equals(resultCode)) {
                return Optional.empty();
            }
            log.error("order-service rejected internal request. status={}, resultCode={}", exception.status(), RemoteResultCodes.loggable(resultCode));
            throw unavailable(exception);
        } catch (DecodeException exception) {
            // 2xx 인데 본문을 읽지 못했다(JSON 아님 · 타입 불일치) — 계약 위반이다
            log.error("order-service response violates contract. type={}", exception.getClass().getSimpleName());
            throw unavailable(exception);
        } catch (FeignException exception) {
            log.warn("order-service call failed. status={}, type={}", exception.status(), exception.getClass().getSimpleName());
            throw unavailable(exception);
        }
        if (response == null) {
            log.error("order-service response violates contract. type=EmptyBody");
            throw new PaymentException(PaymentErrorCode.ORDER_SERVICE_UNAVAILABLE);
        }
        return Optional.of(response);
    }

    /**
     * 주문 요약 응답을 조회 결과로 바꾼다. 요청한 주문 · 회원과 다르거나 칸이 계약과 다르면 503 이다 — 결제 금액 · 주문명을 추측하지 않는다.
     *
     * @throws PaymentException {@code ORDER_SERVICE_UNAVAILABLE} — 봉투 · 본문이 계약과 다르다
     */
    public OrderPaymentSummaryQueryResult toPaymentSummaryResult(Response<OrderPaymentSummaryInternalResponse> response, long orderId, long memberId) {
        try {
            if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
                throw new IllegalArgumentException("success envelope required");
            }
            OrderPaymentSummaryInternalResponse body = response.dataBody();
            if (parseId(body.orderId()) != orderId || parseId(body.memberId()) != memberId) {
                throw new IllegalArgumentException("order or member mismatch");
            }
            if (body.status() == null || body.status().isBlank()) {
                throw new IllegalArgumentException("status required");
            }
            Money totalAmount = toMoney(body.totalAmount());
            String orderName = body.orderName();
            if (orderName == null || orderName.isBlank() || orderName.codePointCount(0, orderName.length()) > Payment.ORDER_NAME_MAX_LENGTH) {
                throw new IllegalArgumentException("order name out of contract");
            }
            if (body.paymentDueAt() == null) {
                throw new IllegalArgumentException("payment due required");
            }
            return new OrderPaymentSummaryQueryResult(orderId, memberId, body.status(), totalAmount, orderName, body.paymentDueAt());
        } catch (RuntimeException exception) {
            log.error("order-service payment summary violates contract. type={}", exception.getClass().getSimpleName());
            throw unavailable(exception);
        }
    }

    private static long parseId(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id required");
        }
        return Long.parseLong(id);
    }

    private static Money toMoney(OrderPaymentSummaryInternalResponse.Amount amount) {
        if (amount == null || amount.amount() == null || amount.amount() <= 0 || amount.currency() == null) {
            throw new IllegalArgumentException("positive amount required");
        }
        return Money.of(amount.amount(), CurrencyCode.valueOf(amount.currency()));
    }

    private static PaymentException unavailable(Throwable cause) {
        return new PaymentException(PaymentErrorCode.ORDER_SERVICE_UNAVAILABLE, cause);
    }

    /** 오류 봉투의 {@code dataHeader.resultCode}. 본문이 없거나 JSON 이 아니거나 코드가 없으면 null 이다. 봉투는 UTF-8 JSON 이다. */
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

    /** 4xx 오류 봉투에서 읽는 칸 — {@code dataHeader.resultCode} 하나다. 나머지 칸은 무시한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorEnvelope(ErrorHeader dataHeader) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorHeader(String resultCode) {
    }
}
