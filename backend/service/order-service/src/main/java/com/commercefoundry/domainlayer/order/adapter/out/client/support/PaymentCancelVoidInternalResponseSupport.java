package com.commercefoundry.domainlayer.order.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.PaymentCancelVoidInternalResponse;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelVoidQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelVoidQueryResult.Outcome;
import com.commercefoundry.global.support.RemoteResultCodes;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import feign.FeignException;
import feign.codec.DecodeException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * payment-service 무환불 판정(void)의 서킷 · 결과 분류 (#17 A4, {@code payment-guide.md} §8-1, {@code coding-conventions.md} §10).
 * {@code FeignException} · 원격 본문 · cause 를 이 밖으로 내보내지 않는다.
 *
 * <p><b>확정은 좁게, 나머지는 닫힌 쪽이다</b> — 무환불을 잘못 확정하면 환불이 나갔거나 나갈 수 있는 취소를 거절해 주문이 다시 출고된다. 그래서
 * {@code NO_REFUND_CONFIRMED} 는 성공 봉투 · 같은 결제 · 같은 클레임 · {@code outcome} 문자열 정확 일치 · {@code sealed=true} 를 모두 만족할 때만이고,
 * 그 밖은 모두 거절을 막는 값({@code REFUNDED} · {@code UNCONFIRMED} · {@code UNAVAILABLE})으로 분류한다.
 *
 * <ul>
 *   <li>서킷 인스턴스는 {@value #CIRCUIT_BREAKER} 다 — 환불 실행({@code payment-cancellation})과 나눠, 판정 장애가 환불 재시도 스케줄러를 막지 않게 한다.
 *       설정은 {@code application.yml} 의 {@code resilience4j.circuitbreaker.instances.payment-cancellation-void}, 4xx 는 세지 않는다.</li>
 *   <li>200 + 계약 — {@code outcome} 그대로다. {@code UNCONFIRMED} 중 이유 {@code PG_UNAVAILABLE}(PG 잔액 조회 장애)만 {@code UNAVAILABLE} 로 본다.</li>
 *   <li>200 인데 계약 위반(실패 봉투 · 본문 없음 · 다른 결제 · 다른 클레임 · 모르는 {@code outcome} · {@code sealed} 가 true 가 아닌 확정) — {@code UNAVAILABLE}(error 로그).</li>
 *   <li>{@code PAYMENT_*} 4xx(404 {@code PAYMENT_001} · 409 {@code PAYMENT_019} · {@code 017} · {@code 012} · 400 검증 코드) — {@code UNCONFIRMED}, 사유는 그 코드.</li>
 *   <li>그 밖의 4xx(코드 없음 · 다른 도메인 코드 · 401 · 403 · 408 · 429) · 5xx · 타임아웃 · 전송 실패 · 서킷 오픈 — {@code UNAVAILABLE}.</li>
 *   <li>로그에는 status · resultCode · 판정 이유(코드 모양일 때만) · 예외 타입 · 결제 · 클레임 아이디만 남긴다.</li>
 * </ul>
 */
@Slf4j
@Component
public class PaymentCancelVoidInternalResponseSupport {

    static final String CIRCUIT_BREAKER = "payment-cancellation-void";
    /** payment 가 PG 잔액 조회에 답을 받지 못했다는 판정 이유 — 판정 장애로 본다. */
    static final String PG_UNAVAILABLE = "PG_UNAVAILABLE";
    static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";
    static final String SERVER_ERROR = "SERVER_ERROR";
    static final String TRANSPORT_FAILURE = "TRANSPORT_FAILURE";
    static final String CLIENT_ERROR = "CLIENT_ERROR";
    static final String CONTRACT_VIOLATION = "CONTRACT_VIOLATION";
    /** 판정 이유가 비었을 때의 사유 코드. */
    static final String UNSPECIFIED_REASON = "UNSPECIFIED";
    /** 판정 이유가 코드 모양이 아닐 때의 사유 코드 — 원격 임의 문자열을 싣지 않는다. */
    static final String UNRECOGNIZED_REASON = "UNRECOGNIZED";
    private static final String PAYMENT_CODE_PREFIX = "PAYMENT_";

    private final CircuitBreaker circuitBreaker;
    private final JsonMapper jsonMapper;

    public PaymentCancelVoidInternalResponseSupport(CircuitBreakerRegistry circuitBreakerRegistry, JsonMapper jsonMapper) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
        this.jsonMapper = jsonMapper;
    }

    /** 원격 호출을 서킷으로 감싸고 결과로 분류한다. Feign · 서킷 예외는 던지지 않는다. */
    public PaymentCancelVoidQueryResult confirmNoRefund(long paymentId, long claimId, Supplier<Response<PaymentCancelVoidInternalResponse>> remoteCall) {
        Response<PaymentCancelVoidInternalResponse> response;
        try {
            response = circuitBreaker.executeSupplier(remoteCall);
        } catch (CallNotPermittedException exception) {
            log.warn("payment-service void circuit open. circuitBreaker={}, paymentId={}, claimId={}", CIRCUIT_BREAKER, paymentId, claimId);
            return unavailable(CIRCUIT_OPEN);
        } catch (FeignException.FeignClientException exception) {
            // 서킷은 4xx 를 세지 않고(ignore-exceptions) 지나보냈다. resultCode 해석은 서킷 밖에서 한다
            return classifyClientError(exception, paymentId, claimId);
        } catch (FeignException exception) {
            log.warn("payment-service void unavailable. status={}, type={}, paymentId={}, claimId={}", exception.status(),
                exception.getClass().getSimpleName(), paymentId, claimId);
            return unavailable(remoteFailureReason(exception));
        }
        return classifyResponse(response, paymentId, claimId);
    }

    private PaymentCancelVoidQueryResult classifyResponse(Response<PaymentCancelVoidInternalResponse> response, long paymentId, long claimId) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
            return contractViolation(paymentId, claimId);
        }
        PaymentCancelVoidInternalResponse body = response.dataBody();
        if (!Long.toString(paymentId).equals(body.paymentId()) || !Long.toString(claimId).equals(body.claimId()) || body.outcome() == null) {
            return contractViolation(paymentId, claimId);
        }
        String reason = loggableReason(body.reason());
        PaymentCancelVoidQueryResult result = switch (body.outcome()) {
            case "NO_REFUND_CONFIRMED" -> Boolean.TRUE.equals(body.sealed()) ? new PaymentCancelVoidQueryResult(Outcome.NO_REFUND_CONFIRMED, reason) : null;
            case "REFUNDED" -> new PaymentCancelVoidQueryResult(Outcome.REFUNDED, reason);
            case "UNCONFIRMED" -> PG_UNAVAILABLE.equals(body.reason()) ? unavailable(PG_UNAVAILABLE) : new PaymentCancelVoidQueryResult(Outcome.UNCONFIRMED, reason);
            default -> null;
        };
        if (result == null) {
            return contractViolation(paymentId, claimId);
        }
        if (result.isNoRefundConfirmed()) {
            log.info("payment-service confirmed no refund. reason={}, paymentId={}, claimId={}", reason, paymentId, claimId);
        } else {
            log.warn("payment-service did not confirm no refund. outcome={}, reason={}, paymentId={}, claimId={}", result.outcome(), reason, paymentId, claimId);
        }
        return result;
    }

    private PaymentCancelVoidQueryResult classifyClientError(FeignException exception, long paymentId, long claimId) {
        String resultCode = RemoteResultCodes.loggable(resultCodeOf(exception));
        int status = exception.status();
        if (resultCode != null && resultCode.startsWith(PAYMENT_CODE_PREFIX)) {
            log.warn("payment-service rejected void. status={}, resultCode={}, paymentId={}, claimId={}", status, resultCode, paymentId, claimId);
            return new PaymentCancelVoidQueryResult(Outcome.UNCONFIRMED, resultCode);
        }
        log.error("payment-service void returned unclassified client error. status={}, resultCode={}, paymentId={}, claimId={}", status, resultCode,
            paymentId, claimId);
        return unavailable(CLIENT_ERROR);
    }

    private static String remoteFailureReason(FeignException exception) {
        if (exception instanceof DecodeException) {
            return CONTRACT_VIOLATION;
        }
        return exception.status() >= 500 ? SERVER_ERROR : TRANSPORT_FAILURE;
    }

    private static PaymentCancelVoidQueryResult contractViolation(long paymentId, long claimId) {
        log.error("payment-service void response violates contract. paymentId={}, claimId={}", paymentId, claimId);
        return unavailable(CONTRACT_VIOLATION);
    }

    private static PaymentCancelVoidQueryResult unavailable(String reasonCode) {
        return new PaymentCancelVoidQueryResult(Outcome.UNAVAILABLE, reasonCode);
    }

    /** 판정 이유를 사유 코드로 — 코드 모양일 때만 그대로이고, 비었으면 {@value #UNSPECIFIED_REASON}, 그 밖은 {@value #UNRECOGNIZED_REASON} 다. */
    private static String loggableReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return UNSPECIFIED_REASON;
        }
        return PaymentCancelVoidQueryResult.isReasonCode(reason) ? reason : UNRECOGNIZED_REASON;
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
