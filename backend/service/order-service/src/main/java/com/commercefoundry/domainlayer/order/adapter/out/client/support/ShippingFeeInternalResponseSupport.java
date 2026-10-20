package com.commercefoundry.domainlayer.order.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.ShippingFeeQuoteInternalResponse;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.query.ShippingFeeQuoteQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.ShippingScope;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * delivery-service internal 호출의 서킷 · 예외 변환 · 응답 변환 ({@code coding-conventions.md} §10). {@code FeignException} 과 원격 응답 모양을 이 밖으로 내보내지 않는다.
 *
 * <ul>
 *   <li>서킷 인스턴스는 {@value #CIRCUIT_BREAKER}(대상 서비스 논리명)다. 설정은 {@code application.yml} 의 {@code resilience4j.circuitbreaker.instances.delivery-service}.</li>
 *   <li>서킷 오픈({@link CallNotPermittedException}) · 전송 실패 · 타임아웃 · 5xx — 503 {@code ORDER_011}.</li>
 *   <li>4xx({@code FeignClientException})는 서킷 안에서 그대로 던져 실패로 세지 않는다({@code ignore-exceptions}) — 고객 입력에 따른 업무 거절(배송할 수 없는 국가 등)이
 *       몰려도 서킷이 열리지 않는다. 본문의 {@code dataHeader.resultCode} 는 <b>서킷 밖</b>(catch)에서 읽어
 *       {@code DELIVERY_001/002/003/011} 만 {@code ORDER_012~015} 로 번역한다. 서킷 안에서 다른 예외로 바꾸면 업무 거절이 서킷 실패로 세어진다.</li>
 *   <li>그 밖의 4xx — 모르는 코드(본문 검증 {@code DELIVERY_1xx} 등 우리 요청 문제) · 본문을 읽지 못함 · 401 {@code SECURITY_007}(서비스 토큰 비밀 · 서비스 ID 설정 문제라
 *       재시도로 풀리지 않는다, {@code security-guide.md} §9-5) · 403(허용 호출자) — 은 error 로그를 남기고 503 {@code ORDER_011} 이다.</li>
 *   <li>{@code ErrorDecoder} 를 쓰지 않는다 — 예외 타입이 바뀌면 {@code ignore-exceptions} 도 같이 바꿔야 한다.</li>
 *   <li>로그에는 status · resultCode · 예외 타입만 남긴다. 요청 본문(우편번호 — 배송지 일부)과 응답 본문은 남기지 않는다 ({@code coding-conventions.md} §8).</li>
 * </ul>
 *
 * <p>컴포넌트 이름에 컨텍스트 접두사(ShippingFee)를 붙인다 — 같은 단순 이름의 {@code @Component} 가 두 패키지에 있으면 기동이 실패한다.
 */
@Slf4j
@Component
public class ShippingFeeInternalResponseSupport {

    static final String CIRCUIT_BREAKER = "delivery-service";

    /** delivery 의 업무 거절 중 주문 계약으로 번역하는 것. 여기 없는 4xx 는 우리 요청 · 설정 문제라 503 이다. */
    private static final Map<String, OrderErrorCode> TRANSLATED_REJECTIONS = Map.of(
        "DELIVERY_001", OrderErrorCode.SHIPPING_UNAVAILABLE,
        "DELIVERY_002", OrderErrorCode.DESTINATION_NOT_SUPPORTED,
        "DELIVERY_003", OrderErrorCode.DESTINATION_CURRENCY_NOT_SUPPORTED,
        "DELIVERY_011", OrderErrorCode.SHIPPING_WEIGHT_NOT_SUPPORTED);

    private final CircuitBreaker circuitBreaker;
    private final JsonMapper jsonMapper;

    /** {@code jsonMapper} 는 서비스 컨텍스트의 빈(Boot 의 Jackson 3 자동설정)이다 — 오류 봉투 하나만 읽는다. */
    public ShippingFeeInternalResponseSupport(CircuitBreakerRegistry circuitBreakerRegistry, JsonMapper jsonMapper) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
        this.jsonMapper = jsonMapper;
    }

    /** 원격 호출을 서킷으로 감싸고 실패를 {@code OrderException} 으로 바꾼다. */
    public <T> T call(Supplier<T> remoteCall) {
        try {
            return circuitBreaker.executeSupplier(remoteCall);
        } catch (CallNotPermittedException exception) {
            log.warn("delivery-service circuit open. circuitBreaker={}", CIRCUIT_BREAKER);
            throw new OrderException(OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE, exception);
        } catch (FeignException.FeignClientException exception) {
            // 서킷은 이 예외를 세지 않고(ignore-exceptions) 지나보냈다. resultCode 해석은 여기 — 서킷 밖에서 한다
            throw translateRejection(exception);
        } catch (FeignException exception) {
            log.warn("delivery-service call failed. status={}, type={}", exception.status(), exception.getClass().getSimpleName());
            throw new OrderException(OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE, exception);
        }
    }

    /**
     * 견적 응답을 조회 결과로 바꾼다. 봉투가 실패이거나 본문이 비었으면 503 이다 — 성공 응답에는 언제나 견적이 있다.
     *
     * @param orderCurrency 요청한 주문 금액의 통화. 견적 통화가 이와 다르면 계약 위반이다 — delivery 는 통화가 다르면 견적하지 않고 {@code DELIVERY_003} 을 낸다(#70)
     * @throws OrderException {@code DELIVERY_SERVICE_UNAVAILABLE} — 봉투 · 본문이 계약과 다르거나 금액 · 통화 · 범위를 읽을 수 없다
     */
    public ShippingFeeQuoteQueryResult toShippingFeeQuoteResult(Response<ShippingFeeQuoteInternalResponse> response, CurrencyCode orderCurrency) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
            log.error("delivery-service quote response is not a success envelope. resultCode={}",
                response == null || response.dataHeader() == null ? null : response.dataHeader().resultCode());
            throw new OrderException(OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE);
        }
        try {
            ShippingFeeQuoteQueryResult result = toQueryResult(response.dataBody());
            if (result.shippingFee().currency() != orderCurrency) {
                throw new IllegalArgumentException("견적 통화가 주문 통화와 다릅니다");
            }
            return result;
        } catch (RuntimeException exception) {
            // 금액 · 통화 · 범위 · 정책 version 이 계약과 다르다(빠진 칸 · 모르는 통화 · 음수 · 모르는 범위 · 통화 섞임). 배송비를 추측하지 않는다
            log.error("delivery-service quote response violates contract. type={}", exception.getClass().getSimpleName());
            throw new OrderException(OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE, exception);
        }
    }

    private OrderException translateRejection(FeignException.FeignClientException exception) {
        String resultCode = resultCodeOf(exception);
        OrderErrorCode errorCode = resultCode == null ? null : TRANSLATED_REJECTIONS.get(resultCode);
        if (errorCode == null) {
            log.error("delivery-service rejected internal request. status={}, resultCode={}", exception.status(), resultCode);
            return new OrderException(OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE, exception);
        }
        if (errorCode == OrderErrorCode.SHIPPING_UNAVAILABLE) {
            // 0원 배송으로 열리지 않게 막았다. 운영자가 배송비 정책을 등록해야 풀린다
            log.error("delivery-service shipping policy is not configured. status={}, resultCode={}", exception.status(), resultCode);
        }
        return new OrderException(errorCode, exception);
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

    private static ShippingFeeQuoteQueryResult toQueryResult(ShippingFeeQuoteInternalResponse body) {
        return new ShippingFeeQuoteQueryResult(
            toMoney(body.shippingFee()), toMoney(body.baseFee()), Objects.requireNonNull(body.freeShippingApplied(), "freeShippingApplied"),
            toMoney(body.remoteAreaSurcharge()), body.remoteAreaName(), toNullableMoney(body.freeShippingThreshold()),
            Objects.requireNonNull(body.shippingPolicyVersion(), "shippingPolicyVersion"), ShippingScope.valueOf(body.scope()),
            body.shippingZoneName(), toNullableMoney(body.weightFee()));
    }

    private static Money toMoney(MoneyItem money) {
        if (money == null) {
            throw new IllegalArgumentException("금액이 없습니다");
        }
        return Money.of(money.amount(), CurrencyCode.valueOf(money.currency()));
    }

    private static Money toNullableMoney(MoneyItem money) {
        return money == null ? null : toMoney(money);
    }

    /** 4xx 오류 봉투에서 읽는 칸 — {@code dataHeader.resultCode} 하나다. 나머지 칸은 무시한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorEnvelope(ErrorHeader dataHeader) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorHeader(String resultCode) {
    }
}
