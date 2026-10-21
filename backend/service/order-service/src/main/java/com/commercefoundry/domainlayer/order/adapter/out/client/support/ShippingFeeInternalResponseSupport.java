package com.commercefoundry.domainlayer.order.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.ShippingFeeQuoteInternalResponse;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.query.ShippingFeeQuoteQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.ShippingFeeQuote;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
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
 *   <li>로그에는 status · resultCode · 예외 타입만 남긴다. resultCode 는 코드 모양일 때만 그대로 남기고 아니면 {@value #UNRECOGNIZED_RESULT_CODE} 다 —
 *       원격의 임의 문자열(CR/LF · 긴 값)을 로그에 넣지 않는다. 요청 본문(우편번호 — 배송지 일부)과 응답 본문은 남기지 않는다 ({@code coding-conventions.md} §8).
 *       원인 예외({@code FeignException})의 메시지에는 delivery 응답 본문(최대 200자)이 들어 있으므로, 이 클래스가 던진 {@code OrderException} 을 받는 핸들러는
 *       원인 예외를 throwable 로 로그하지 않는다(코드 · 원인 타입만).</li>
 * </ul>
 *
 * <p>컴포넌트 이름에 대상 원격 자원 접두사(ShippingFee)를 붙인다 — 같은 단순 이름의 {@code @Component} 가 두 패키지에 있으면 기동이 실패한다.
 * catalog 의 {@code ProductInternalResponseSupport} 는 대상 서비스 접두사라, 같은 order-service 에서 product-service 재고 선점 support 는 서비스 이름이 아니라
 * 원격 자원으로 짓는다(예: {@code StockReservationInternalResponseSupport}).
 */
@Slf4j
@Component
public class ShippingFeeInternalResponseSupport {

    static final String CIRCUIT_BREAKER = "delivery-service";
    static final String UNRECOGNIZED_RESULT_CODE = "<unrecognized>";

    /**
     * 로그에 그대로 남기는 resultCode 의 모양 — common-core {@code ValidationErrorSupport} 의 코드 판정({@code DOMAIN_NNN})과 같다.
     * 그쪽 판정은 private 이라 가져다 쓰지 않고 같은 식을 둔다.
     */
    private static final Pattern RESULT_CODE_SHAPE = Pattern.compile("[A-Z][A-Z0-9_]*_[0-9]{3}");
    private static final int MAX_LOGGED_RESULT_CODE_LENGTH = 40;

    /**
     * delivery 의 업무 거절 중 주문 계약으로 번역하는 것. 여기 없는 4xx 는 우리 요청 · 설정 문제라 503 이다.
     *
     * <p>번역 대상이 늘면 {@code ShippingFeeClientAdapter} 의 {@code DESTINATION_SUPPORTED_REJECTIONS}(확인용 견적의 거절 중 "국가는 배송 가능" 으로 읽는 것)도 본다.
     */
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
     * @param orderAmount 요청한 주문 금액(할인 후 상품 합계). 견적 통화가 이 통화와 다르면 계약 위반이다 — delivery 는 통화가 다르면 견적하지 않고
     *     {@code DELIVERY_003} 을 낸다(#70). 무료배송 판정이 이 금액 · 기준과 맞지 않거나 배송비가 산식과 달라도 계약 위반이다
     * @param countryCode 요청한 배송 국가. KR 이면 {@code DOMESTIC} 이고 국가 그룹 · 무게 요금이 없어야 한다. 그 밖이면 {@code INTERNATIONAL} 이고
     *     국가 그룹 · 무게 요금이 있으며 도서산간 추가 배송비 0 · 구간 이름 없음이어야 한다. 어긋나면 계약 위반이다
     * @throws OrderException {@code DELIVERY_SERVICE_UNAVAILABLE} — 봉투 · 본문이 계약과 다르거나 금액 · 통화 · 범위를 읽을 수 없거나 범위가 요청 국가와 맞지 않거나
     *     배송비가 산식과 다르다
     */
    public ShippingFeeQuoteQueryResult toShippingFeeQuoteResult(Response<ShippingFeeQuoteInternalResponse> response, Money orderAmount, String countryCode) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
            log.error("delivery-service quote response is not a success envelope. resultCode={}",
                response == null || response.dataHeader() == null ? null : loggableResultCode(response.dataHeader().resultCode()));
            throw new OrderException(OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE);
        }
        try {
            ShippingFeeQuoteQueryResult result = toQueryResult(response.dataBody());
            if (result.shippingFee().currency() != orderAmount.currency()) {
                throw new IllegalArgumentException("견적 통화가 주문 통화와 다릅니다");
            }
            requireScopeOf(result, countryCode);
            requireFeeFormula(result, orderAmount);
            return result;
        } catch (RuntimeException exception) {
            // 금액 · 통화 · 범위 · 정책 version 이 계약과 다르다(빠진 칸 · 모르는 통화 · 음수 · 모르는 범위 · 통화 섞임 · 요청 국가와 다른 범위 · 산식 · 무료배송 판정 불일치).
            // 배송비를 추측하지 않는다
            log.error("delivery-service quote response violates contract. type={}", exception.getClass().getSimpleName());
            throw new OrderException(OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE, exception);
        }
    }

    /**
     * 원격이 보낸 resultCode 를 로그에 넣을 값으로 바꾼다. 코드 모양({@code [A-Z][A-Z0-9_]*_[0-9]{3}}, 40자 이하)일 때만 그대로이고,
     * 아니면(CR/LF · 긴 값 · 임의 문자열) {@value #UNRECOGNIZED_RESULT_CODE} 다. 코드가 없으면(본문 없음 · JSON 아님) null 그대로다 — 원격 문자열이 아니다.
     */
    static String loggableResultCode(String resultCode) {
        if (resultCode == null) {
            return null;
        }
        return resultCode.length() <= MAX_LOGGED_RESULT_CODE_LENGTH && RESULT_CODE_SHAPE.matcher(resultCode).matches()
            ? resultCode : UNRECOGNIZED_RESULT_CODE;
    }

    private OrderException translateRejection(FeignException.FeignClientException exception) {
        String resultCode = resultCodeOf(exception);
        OrderErrorCode errorCode = resultCode == null ? null : TRANSLATED_REJECTIONS.get(resultCode);
        if (errorCode == null) {
            log.error("delivery-service rejected internal request. status={}, resultCode={}", exception.status(), loggableResultCode(resultCode));
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

    /**
     * 견적 범위가 요청 국가와 맞고, 범위별 칸이 계약대로인가. delivery 는 KR 이면 국내 정책, 그 밖이면 해외 정책으로만 견적한다.
     * 요청 국가는 {@code ShippingFeeQuoteQuery} 가 형식을 이미 확인했다 — 형식이 아니면(조립 버그) 여기서도 {@link IllegalArgumentException} 이고 부른 쪽의 계약 위반 경로(503)로 간다.
     */
    private static void requireScopeOf(ShippingFeeQuoteQueryResult result, String countryCode) {
        ShippingScope requested = ShippingScope.ofCountry(countryCode)
            .orElseThrow(() -> new IllegalArgumentException("요청 배송 국가 코드가 ISO 3166-1 alpha-2 대문자 형식이 아닙니다"));
        boolean consistent = requested == ShippingScope.DOMESTIC
            ? result.scope() == ShippingScope.DOMESTIC && result.shippingZoneName() == null && result.weightFee() == null
            : result.scope() == ShippingScope.INTERNATIONAL && result.shippingZoneName() != null && result.weightFee() != null
                && result.remoteAreaSurcharge().isZero() && result.remoteAreaName() == null;
        if (!consistent) {
            throw new IllegalArgumentException("견적 범위나 범위별 칸이 요청 국가와 맞지 않습니다");
        }
    }

    /**
     * 받을 배송비가 delivery 의 산식({@link ShippingFeeQuote#expectedShippingFee})대로이고, 무료배송 판정이 요청한 주문 금액과 맞는가.
     * delivery 는 기준이 있고 주문 금액이 기준 <b>이상</b>이면 무료배송이다 — {@code ShippingPolicy#quote} · {@code InternationalShippingPolicy#quote} 의
     * {@code freeShippingThreshold != null && !freeShippingThreshold.isGreaterThan(orderAmount)} 와 같은 방향으로 비교한다.
     */
    private static void requireFeeFormula(ShippingFeeQuoteQueryResult result, Money orderAmount) {
        Money threshold = result.freeShippingThreshold();
        boolean expectedFreeShipping = threshold != null && !threshold.isGreaterThan(orderAmount);
        if (result.freeShippingApplied() != expectedFreeShipping) {
            throw new IllegalArgumentException("무료배송 판정이 무료배송 기준 · 주문 금액과 맞지 않습니다");
        }
        Money expected = ShippingFeeQuote.expectedShippingFee(
            result.scope(), result.baseFee(), result.freeShippingApplied(), result.remoteAreaSurcharge(), result.weightFee());
        if (!result.shippingFee().equals(expected)) {
            throw new IllegalArgumentException("배송비가 산식과 다릅니다");
        }
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
