package com.commercefoundry.domainlayer.order.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.StockReservationInternalResponse;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException.Failure;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * product-service 재고 선점 · 해제 호출의 서킷 · 예외 변환 · 응답 변환 ({@code coding-conventions.md} §10). {@code FeignException} 과 원격 응답 모양을 이 밖으로 내보내지 않는다.
 * 실패는 {@link StockReservationException} 사유로만 알리고, 사용자 오류 코드는 {@code OrderStockProcessor} 가 정한다 ({@code order-flow-guide.md} §3-4).
 *
 * <ul>
 *   <li>서킷 인스턴스는 {@value #CIRCUIT_BREAKER} 다 — catalog 의 SKU 조회와 같은 인스턴스(같은 대상의 장애)를 쓴다.</li>
 *   <li>서킷 오픈 · 전송 실패 · 타임아웃 · 5xx — {@link Failure#UNKNOWN}(선점이 됐는지 모른다).</li>
 *   <li>4xx({@code FeignClientException})는 서킷 안에서 그대로 던져 실패로 세지 않는다({@code ignore-exceptions}) — 재고 부족이 몰려도 서킷이 열리지 않는다.
 *       본문의 {@code dataHeader.resultCode} 는 <b>서킷 밖</b>(catch)에서 읽어 아는 코드만 사유로 바꾼다. 모르는 코드 · 본문을 읽지 못함 · 401 {@code SECURITY_007} 은
 *       error 로그를 남기고 {@link Failure#REJECTED} 다.</li>
 *   <li>원격 오류 메시지는 읽지 않는다 — {@code PRODUCT_021} · {@code PRODUCT_022} 메시지에는 SKU 아이디 목록이 있고 고객 응답에 넘기지 않는다.</li>
 *   <li>로그에는 status · resultCode(코드 모양일 때만, {@link InternalResultCodes#loggable}) · 예외 타입만 남긴다. 원인 예외의 메시지에는
 *       product 응답 본문이 들어 있으므로 throwable 로 로그하지 않는다.</li>
 * </ul>
 *
 * <p>컴포넌트 이름에 대상 원격 자원 접두사(StockReservation)를 붙인다 — catalog 의 {@code ProductInternalResponseSupport} 와 겹치지 않게.
 */
@Slf4j
@Component
public class StockReservationInternalResponseSupport {

    static final String CIRCUIT_BREAKER = "product-service";

    /** product-service 의 재고 업무 코드 → 사유. 여기 없는 4xx 는 {@link Failure#REJECTED} 다. */
    static final Map<String, Failure> FAILURES = Map.of(
        "PRODUCT_003", Failure.NOT_FOUND,
        "PRODUCT_021", Failure.INSUFFICIENT,
        "PRODUCT_022", Failure.NOT_PURCHASABLE,
        "PRODUCT_023", Failure.ITEMS_MISMATCH,
        "PRODUCT_024", Failure.ALREADY_CONFIRMED,
        "PRODUCT_025", Failure.ALREADY_RELEASED,
        "PRODUCT_026", Failure.CONFLICT);

    private final CircuitBreaker circuitBreaker;
    private final JsonMapper jsonMapper;

    /** {@code jsonMapper} 는 서비스 컨텍스트의 빈(Boot 의 Jackson 3 자동설정)이다 — 오류 봉투 하나만 읽는다. */
    public StockReservationInternalResponseSupport(CircuitBreakerRegistry circuitBreakerRegistry, JsonMapper jsonMapper) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
        this.jsonMapper = jsonMapper;
    }

    /**
     * 원격 호출을 서킷으로 감싸고 성공 봉투의 본문을 돌려준다.
     *
     * @throws StockReservationException 사유는 {@link Failure}
     */
    public StockReservationInternalResponse call(Supplier<Response<StockReservationInternalResponse>> remoteCall) {
        Response<StockReservationInternalResponse> response;
        try {
            response = circuitBreaker.executeSupplier(remoteCall);
        } catch (CallNotPermittedException exception) {
            log.warn("product-service circuit open. circuitBreaker={}", CIRCUIT_BREAKER);
            throw new StockReservationException(Failure.UNKNOWN, exception);
        } catch (FeignException.FeignClientException exception) {
            // 서킷은 이 예외를 세지 않고(ignore-exceptions) 지나보냈다. resultCode 해석은 여기 — 서킷 밖에서 한다
            throw translateRejection(exception);
        } catch (FeignException exception) {
            log.warn("product-service stock call failed. status={}, type={}", exception.status(), exception.getClass().getSimpleName());
            throw new StockReservationException(Failure.UNKNOWN, exception);
        }
        if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
            log.error("product-service stock response is not a success envelope. resultCode={}",
                response == null || response.dataHeader() == null ? null : InternalResultCodes.loggable(response.dataHeader().resultCode()));
            throw new StockReservationException(Failure.UNKNOWN);
        }
        return response.dataBody();
    }

    /**
     * 선점 응답을 조회 결과로 바꾼다. 주문 아이디 대조 · 만료 시각 확인은 부른 Processor 가 한다.
     *
     * @throws StockReservationException {@link Failure#UNKNOWN} — 아이디 · 만료 시각을 읽을 수 없다. 선점이 됐는지 모른다
     */
    public StockReservationQueryResult toQueryResult(StockReservationInternalResponse body) {
        try {
            return new StockReservationQueryResult(Long.parseLong(body.reservationId()), Long.parseLong(body.orderId()), body.expiresAt());
        } catch (RuntimeException exception) {
            log.error("product-service stock response violates contract. type={}", exception.getClass().getSimpleName());
            throw new StockReservationException(Failure.UNKNOWN, exception);
        }
    }

    private StockReservationException translateRejection(FeignException.FeignClientException exception) {
        String resultCode = InternalResultCodes.resultCodeOf(exception, jsonMapper);
        Failure failure = resultCode == null ? null : FAILURES.get(resultCode);
        if (failure == null) {
            // 서비스 토큰 · 요청 형식 등 우리 쪽 설정 · 계약 문제 — 다시 보내도 같다 (security-guide.md §9-5)
            log.error("product-service rejected stock request. status={}, resultCode={}", exception.status(), InternalResultCodes.loggable(resultCode));
            return new StockReservationException(Failure.REJECTED, exception);
        }
        return new StockReservationException(failure, exception);
    }
}
