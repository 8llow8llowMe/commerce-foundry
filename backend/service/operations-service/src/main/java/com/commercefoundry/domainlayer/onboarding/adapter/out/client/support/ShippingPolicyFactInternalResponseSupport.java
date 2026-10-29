package com.commercefoundry.domainlayer.onboarding.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.onboarding.adapter.out.client.feign.dto.ShippingPolicyFactInternalResponse;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingErrorCode;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingException;
import com.commercefoundry.domainlayer.onboarding.application.port.out.query.ShippingPolicyFactQueryResult;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * delivery-service 온보딩 사실 호출의 서킷 · 예외 변환 · 응답 변환 ({@code coding-conventions.md} §10).
 * {@code FeignException} 과 원격 응답 모양을 이 밖으로 내보내지 않는다. 로그에는 status · 예외 타입 · 코드 모양의 resultCode 만 남긴다(memberId 도 남기지 않는다).
 *
 * <ul>
 *   <li>서킷 인스턴스는 {@value #CIRCUIT_BREAKER}. 설정은 {@code application.yml} 의 {@code resilience4j.circuitbreaker.instances.delivery-service}.</li>
 *   <li>서킷 오픈 · 전송 실패 · 타임아웃 · 5xx — {@code ONBOARDING_030}.</li>
 *   <li>4xx 는 서킷 실패로 세지 않는다({@code ignore-exceptions}). 결과는 같은 {@code ONBOARDING_030} 이다 — memberId 는 언제나 토큰의 양수라
 *       4xx 는 서비스 토큰 · 허용 호출자 설정 문제이고 재시도로 풀리지 않는다. error 로그를 남긴다.</li>
 * </ul>
 *
 * <p>{@code ONBOARDING_030} 은 응답으로 나가지 않는다 — Processor 가 그 업무를 {@code UNAVAILABLE} 로 흡수한다.
 */
@Slf4j
@Component
public class ShippingPolicyFactInternalResponseSupport {

    static final String CIRCUIT_BREAKER = "delivery-service";

    private static final Pattern RESULT_CODE_SHAPE = Pattern.compile("[A-Z][A-Z0-9_]*_[0-9]{3}");
    private static final int MAX_LOGGED_LENGTH = 40;

    private final CircuitBreaker circuitBreaker;

    public ShippingPolicyFactInternalResponseSupport(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
    }

    public <T> T call(Supplier<T> remoteCall) {
        try {
            return circuitBreaker.executeSupplier(remoteCall);
        } catch (CallNotPermittedException exception) {
            log.warn("delivery-service circuit open. circuitBreaker={}", CIRCUIT_BREAKER);
            throw new OnboardingException(OnboardingErrorCode.DELIVERY_SERVICE_UNAVAILABLE, exception);
        } catch (FeignException.FeignClientException exception) {
            log.error("delivery-service rejected shipping policy fact request. status={}", exception.status());
            throw new OnboardingException(OnboardingErrorCode.DELIVERY_SERVICE_UNAVAILABLE, exception);
        } catch (FeignException exception) {
            log.warn("delivery-service shipping policy fact call failed. status={}, type={}", exception.status(), exception.getClass().getSimpleName());
            throw new OnboardingException(OnboardingErrorCode.DELIVERY_SERVICE_UNAVAILABLE, exception);
        }
    }

    /**
     * @throws OnboardingException {@code DELIVERY_SERVICE_UNAVAILABLE} — 성공 봉투가 아니거나 본문 · {@code storeReady} 가 없다. false 로 추측하지 않는다
     */
    public ShippingPolicyFactQueryResult toQueryResult(Response<ShippingPolicyFactInternalResponse> response) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success()
            || response.dataBody() == null || response.dataBody().storeReady() == null) {
            log.error("delivery-service shipping policy fact response violates contract. resultCode={}",
                response == null || response.dataHeader() == null ? null : loggable(response.dataHeader().resultCode()));
            throw new OnboardingException(OnboardingErrorCode.DELIVERY_SERVICE_UNAVAILABLE);
        }
        return new ShippingPolicyFactQueryResult(response.dataBody().storeReady(), response.dataBody().memberFirstSavedAt());
    }

    /** 코드 모양일 때만 그대로 남긴다. 원격의 임의 문자열을 로그에 넣지 않는다. */
    private static String loggable(String resultCode) {
        if (resultCode == null) {
            return null;
        }
        return resultCode.length() <= MAX_LOGGED_LENGTH && RESULT_CODE_SHAPE.matcher(resultCode).matches() ? resultCode : "<unrecognized>";
    }
}
