package com.commercefoundry.domainlayer.shipment.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shipment.adapter.out.client.feign.dto.OrderFulfillmentInternalResponse;
import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException;
import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException.Failure;
import com.commercefoundry.domainlayer.shipment.domain.model.OrderShipmentConfirmation;
import com.commercefoundry.global.support.RemoteResultCodes;
import com.commercefoundry.shared.commerce.ShippingScope;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * order-service 출고 반영 호출의 서킷 · 예외 변환 · 응답 변환 ({@code coding-conventions.md} §10). {@code FeignException} 과 원격 응답 모양을 이 밖으로 내보내지 않는다.
 * 번역의 정본은 {@code order-flow-guide.md} §4 실패 표다.
 *
 * <ul>
 *   <li>서킷 인스턴스는 {@value #CIRCUIT_BREAKER}. 설정은 {@code application.yml} 의 {@code resilience4j.circuitbreaker.instances.order-service}.</li>
 *   <li>4xx 는 서킷 실패로 세지 않는다({@code ignore-exceptions}) — 업무 거절이다. 서킷 밖에서 HTTP 상태와 {@code resultCode} 를 <b>둘 다</b> 보고 나눈다:
 *       404 {@code ORDER_001} → {@link Failure#NOT_FOUND}, 409 {@code ORDER_020} → {@link Failure#NOT_SHIPPABLE}, 409 {@code ORDER_025} → {@link Failure#CONFLICT_RETRY},
 *       400 {@code ORDER_159} · {@code 160} · {@code 161} → {@link Failure#INVALID_TIME}. <b>같은 409 라도 코드로 나눈다</b> — 020 은 행을 지우고 025 는 남긴다.</li>
 *   <li>그 밖의 4xx(모르는 코드 · 본문 없음 · 401 {@code SECURITY_007} 등) · 5xx · 타임아웃 · 전송 실패 · 서킷 오픈 · 계약 위반 응답 → {@link Failure#UNKNOWN}(행을 남긴다).</li>
 *   <li>로그에는 HTTP 상태 · resultCode(코드 모양일 때만) · 예외 타입만 남긴다. 응답 본문 · 메시지 · throwable 은 남기지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class OrderShipmentInternalResponseSupport {

    static final String CIRCUIT_BREAKER = "order-service";

    /** HTTP 상태별로 아는 업무 코드. 상태와 코드가 함께 맞아야 한다 — 다른 상태로 온 같은 코드는 계약 밖이라 결과 모름이다. */
    private static final Map<Integer, Map<String, Failure>> FAILURES = Map.of(
        404, Map.of("ORDER_001", Failure.NOT_FOUND),
        409, Map.of("ORDER_020", Failure.NOT_SHIPPABLE, "ORDER_025", Failure.CONFLICT_RETRY),
        400, Map.of("ORDER_159", Failure.INVALID_TIME, "ORDER_160", Failure.INVALID_TIME, "ORDER_161", Failure.INVALID_TIME)
    );

    /**
     * order {@code ORDER_020} 메시지("현재 주문 상태(취소 요청)에서는 할 수 없는 요청입니다.")에서 상태 이름을 꺼낸다. 한글 · 공백 20자까지만 받는다 —
     * 원격 문자열을 운영자 화면에 그대로 옮기지 않게. 맞지 않으면 이름 없이 일반 문구로 낸다.
     */
    private static final Pattern ORDER_STATUS_NAME = Pattern.compile("^현재 주문 상태\\(([가-힣 ]{1,20})\\)");
    private static final Pattern ORDER_STATUS_CODE = Pattern.compile("[A-Z][A-Z_]{0,39}");
    private static final Set<Character> NON_DECIMAL_FIRST = Set.of('0', '-', '+');

    private final CircuitBreaker circuitBreaker;
    private final JsonMapper jsonMapper;

    public OrderShipmentInternalResponseSupport(CircuitBreakerRegistry circuitBreakerRegistry, JsonMapper jsonMapper) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
        this.jsonMapper = jsonMapper;
    }

    /**
     * @throws OrderShipmentException 200 이 아니다
     */
    public Response<OrderFulfillmentInternalResponse> call(Supplier<Response<OrderFulfillmentInternalResponse>> remoteCall) {
        try {
            return circuitBreaker.executeSupplier(remoteCall);
        } catch (FeignException.FeignClientException exception) {
            // 4xx 는 서킷 설정이 실패로 세지 않는다 — 서킷 밖에서 번역한다
            throw translate(exception);
        } catch (FeignException | CallNotPermittedException exception) {
            log.warn("order-service shipment call failed. status={}, type={}",
                exception instanceof FeignException feignException ? feignException.status() : null, exception.getClass().getSimpleName());
            throw new OrderShipmentException(Failure.UNKNOWN, exception);
        }
    }

    private OrderShipmentException translate(FeignException.FeignClientException exception) {
        ErrorHeader header = errorHeaderOf(exception);
        String code = header == null ? null : header.resultCode();
        Failure failure = code == null ? null : FAILURES.getOrDefault(exception.status(), Map.of()).get(code);
        if (failure == null) {
            log.error("order-service rejected shipment with an unknown response. status={}, resultCode={}", exception.status(), RemoteResultCodes.loggable(code));
            return new OrderShipmentException(Failure.UNKNOWN, exception);
        }
        String statusName = failure == Failure.NOT_SHIPPABLE ? orderStatusNameOf(header.resultMessage()) : null;
        return new OrderShipmentException(failure, statusName, exception);
    }

    /**
     * 200 응답을 확인한다 — 성공 봉투 · 보낸 주문과 같은 아이디 · 양수 회원 아이디 · 상태 코드 모양. 배송 국가를 읽지 못하면 범위 없이(null) 받는다 —
     * 주문은 이미 배송 중이 됐으므로 국가 하나 때문에 반영 완료를 미루지 않는다.
     *
     * <p>계약 위반(회원 아이디 형식 등)은 송장 행을 남기되 error 로그다 — 같은 요청을 다시 보내도 같은 200 이 와서 <b>코드를 고치기 전까지 미반영으로 남는다</b>
     * ({@code order-flow-guide.md} §4).
     *
     * @throws OrderShipmentException {@link Failure#UNKNOWN} — 계약과 다르다(반영됐는지 단정할 수 없다)
     */
    public OrderShipmentConfirmation toConfirmation(Response<OrderFulfillmentInternalResponse> response, long orderId) {
        try {
            if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
                throw new IllegalArgumentException("success envelope required");
            }
            OrderFulfillmentInternalResponse body = response.dataBody();
            if (positiveId(body.orderId()) != orderId) {
                throw new IllegalArgumentException("orderId mismatch");
            }
            long memberId = positiveId(body.memberId());
            if (body.status() == null || !ORDER_STATUS_CODE.matcher(body.status()).matches()) {
                throw new IllegalArgumentException("status shape");
            }
            Optional<ShippingScope> scope = ShippingScope.ofCountry(body.countryCode());
            if (scope.isEmpty()) {
                log.warn("order-service shipment response has no readable country. orderId={}", orderId);
            }
            return new OrderShipmentConfirmation(orderId, memberId, body.status(), scope.orElse(null));
        } catch (RuntimeException exception) {
            log.error("order-service shipment response violates contract. orderId={}, type={}, resultCode={}", orderId, exception.getClass().getSimpleName(),
                response == null || response.dataHeader() == null ? null : RemoteResultCodes.loggable(response.dataHeader().resultCode()));
            throw new OrderShipmentException(Failure.UNKNOWN, exception);
        }
    }

    private static long positiveId(String id) {
        if (id == null || id.isEmpty() || NON_DECIMAL_FIRST.contains(id.charAt(0))) {
            throw new IllegalArgumentException("id shape");
        }
        long value = Long.parseLong(id);
        if (value <= 0) {
            throw new IllegalArgumentException("positive id required");
        }
        return value;
    }

    private static String orderStatusNameOf(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = ORDER_STATUS_NAME.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        String name = matcher.group(1).strip();
        return name.isEmpty() ? null : name;
    }

    private ErrorHeader errorHeaderOf(FeignException exception) {
        try {
            ErrorEnvelope envelope = jsonMapper.readValue(exception.contentUTF8(), ErrorEnvelope.class);
            return envelope == null ? null : envelope.dataHeader();
        } catch (JacksonException | IllegalArgumentException exceptionWithoutBody) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorEnvelope(ErrorHeader dataHeader) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorHeader(String resultCode, String resultMessage) {
    }
}
