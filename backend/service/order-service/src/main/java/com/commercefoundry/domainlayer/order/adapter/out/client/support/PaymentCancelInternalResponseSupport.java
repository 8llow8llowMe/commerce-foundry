package com.commercefoundry.domainlayer.order.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.PaymentCancelInternalResponse;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelQueryResult;
import com.commercefoundry.global.support.RemoteResultCodes;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * payment-service internal 결제 취소의 서킷 · 결과 분류 (#16 PR-2, {@code coding-conventions.md} §10). {@code FeignException} · 원격 본문 · cause 를 이 밖으로 내보내지 않는다.
 *
 * <p><b>종결 거절은 허용 목록만이다</b> — 종결로 잘못 보면 돈은 나갔는데 클레임이 REFUND_FAILED 로 멈출 수 있어, 모르는 응답은 모두 미확정으로 다시 보낸다
 * (같은 클레임 아이디라 payment 가 멱등으로 받는다).
 *
 * <ul>
 *   <li>서킷 인스턴스는 {@value #CIRCUIT_BREAKER} 다 — 결제 조회(#308 {@code payment-service})와 나눠, PG 취소 지연 · 장애가 승인 반영의 재조회를 막지 않게 한다.
 *       설정은 {@code application.yml} 의 {@code resilience4j.circuitbreaker.instances.payment-cancellation}, 4xx 는 세지 않는다.</li>
 *   <li>200 + 계약(성공 봉투 · 같은 결제 · 같은 클레임 · 같은 금액) — {@code SUCCEEDED}. 200 인데 계약과 다르면 {@code UNKNOWN}(error 로그).</li>
 *   <li>{@code REJECTED} — 4xx 봉투의 {@code PAYMENT_016}(취소 불가 · 겹침) · {@code PAYMENT_017}(남은 금액 초과) · {@code PAYMENT_019}(키 재사용) ·
 *       {@code PAYMENT_001}(없는 결제), 그리고 400 의 payment 검증 코드({@code PAYMENT_1xx}). 요청을 바꾸지 않으면 다시 보내도 같다.</li>
 *   <li>{@code UNKNOWN} — 그 밖의 전부. 409 {@code PAYMENT_012}(PG 취소는 성공했는데 payment 저장 실패) · 코드 없는 409 · 408 · 429 · 봉투나 {@code PAYMENT_} 코드가 없는 4xx ·
 *       401 · 403(서비스 토큰 설정) · 5xx(503 {@code PAYMENT_018} 포함) · 타임아웃 · 전송 실패 · 서킷 오픈.</li>
 *   <li>로그에는 status · resultCode(코드 모양일 때만, {@link RemoteResultCodes}) · 예외 타입 · 결제 · 클레임 아이디만 남긴다.</li>
 * </ul>
 */
@Slf4j
@Component
public class PaymentCancelInternalResponseSupport {

    static final String CIRCUIT_BREAKER = "payment-cancellation";
    private static final Set<String> TERMINAL_CODES = Set.of("PAYMENT_016", "PAYMENT_017", "PAYMENT_019", "PAYMENT_001");
    /** payment 의 요청 검증 대역 — 400 일 때만 종결로 본다. */
    private static final Pattern VALIDATION_CODE = Pattern.compile("PAYMENT_1[0-9]{2}");

    private final CircuitBreaker circuitBreaker;
    private final JsonMapper jsonMapper;

    public PaymentCancelInternalResponseSupport(CircuitBreakerRegistry circuitBreakerRegistry, JsonMapper jsonMapper) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
        this.jsonMapper = jsonMapper;
    }

    /** 원격 호출을 서킷으로 감싸고 결과로 분류한다. Feign · 서킷 예외는 던지지 않는다. */
    public PaymentCancelQueryResult cancel(long paymentId, long claimId, long amount, Supplier<Response<PaymentCancelInternalResponse>> remoteCall) {
        Response<PaymentCancelInternalResponse> response;
        try {
            response = circuitBreaker.executeSupplier(remoteCall);
        } catch (CallNotPermittedException exception) {
            log.warn("payment-service cancellation circuit open. circuitBreaker={}, paymentId={}, claimId={}", CIRCUIT_BREAKER, paymentId, claimId);
            return PaymentCancelQueryResult.unknown();
        } catch (FeignException.FeignClientException exception) {
            // 서킷은 4xx 를 세지 않고(ignore-exceptions) 지나보냈다. resultCode 해석은 서킷 밖에서 한다
            return classifyClientError(exception, paymentId, claimId);
        } catch (FeignException exception) {
            log.warn("payment-service cancellation unconfirmed. status={}, type={}, paymentId={}, claimId={}", exception.status(),
                exception.getClass().getSimpleName(), paymentId, claimId);
            return PaymentCancelQueryResult.unknown();
        }
        if (!matchesContract(response, paymentId, claimId, amount)) {
            log.error("payment-service cancellation response violates contract. paymentId={}, claimId={}", paymentId, claimId);
            return PaymentCancelQueryResult.unknown();
        }
        return PaymentCancelQueryResult.succeeded();
    }

    private PaymentCancelQueryResult classifyClientError(FeignException exception, long paymentId, long claimId) {
        String resultCode = RemoteResultCodes.loggable(resultCodeOf(exception));
        int status = exception.status();
        boolean terminal = resultCode != null && (TERMINAL_CODES.contains(resultCode) || (status == 400 && VALIDATION_CODE.matcher(resultCode).matches()));
        if (terminal) {
            log.warn("payment-service rejected cancellation. status={}, resultCode={}, paymentId={}, claimId={}", status, resultCode, paymentId, claimId);
            return PaymentCancelQueryResult.rejected(resultCode);
        }
        log.error("payment-service cancellation returned unclassified client error; will retry. status={}, resultCode={}, paymentId={}, claimId={}", status,
            resultCode, paymentId, claimId);
        return PaymentCancelQueryResult.unknown();
    }

    private static boolean matchesContract(Response<PaymentCancelInternalResponse> response, long paymentId, long claimId, long amount) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
            return false;
        }
        PaymentCancelInternalResponse body = response.dataBody();
        return Long.toString(paymentId).equals(body.paymentId()) && Long.toString(claimId).equals(body.claimId())
            && body.canceledAmount() != null && body.canceledAmount() == amount;
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
