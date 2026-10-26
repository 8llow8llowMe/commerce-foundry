package com.commercefoundry.domainlayer.shippingfee.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.client.feign.dto.MemberDisplayNameInternalItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.client.feign.dto.MemberDisplayNameInternalResponse;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * auth-service 회원 표시 이름 호출의 서킷 · 예외 변환 · 응답 변환 ({@code coding-conventions.md} §10).
 * {@code FeignException} 과 원격 응답 모양을 이 밖으로 내보내지 않는다. 이름 원문은 이 응답에 없고, 마스킹된 값도 로그에 남기지 않는다.
 *
 * <ul>
 *   <li>서킷 인스턴스는 {@value #CIRCUIT_BREAKER}. 설정은 {@code application.yml} 의 {@code resilience4j.circuitbreaker.instances.auth-service}.</li>
 *   <li>서킷 오픈 · 전송 실패 · 타임아웃 · 5xx — 503 {@code DELIVERY_013}.</li>
 *   <li>4xx 는 서킷 실패로 세지 않는다({@code ignore-exceptions}). 사용자에게는 같은 503 으로 답한다. 요청 형식은 우리가 맞추므로 재시도로 풀리지 않는 설정 문제다.</li>
 * </ul>
 */
@Slf4j
@Component
public class MemberDisplayNameInternalResponseSupport {

    static final String CIRCUIT_BREAKER = "auth-service";

    private static final Pattern RESULT_CODE_SHAPE = Pattern.compile("[A-Z][A-Z0-9_]*_[0-9]{3}");
    private static final int MAX_LOGGED_LENGTH = 40;

    private final CircuitBreaker circuitBreaker;

    public MemberDisplayNameInternalResponseSupport(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
    }

    public <T> T call(Supplier<T> remoteCall) {
        try {
            return circuitBreaker.executeSupplier(remoteCall);
        } catch (CallNotPermittedException exception) {
            log.warn("auth-service circuit open. circuitBreaker={}", CIRCUIT_BREAKER);
            throw new DeliveryException(DeliveryErrorCode.AUTH_SERVICE_UNAVAILABLE, exception);
        } catch (FeignException.FeignClientException exception) {
            log.error("auth-service rejected member display name request. status={}", exception.status());
            throw new DeliveryException(DeliveryErrorCode.AUTH_SERVICE_UNAVAILABLE, exception);
        } catch (FeignException exception) {
            log.warn("auth-service member display name call failed. status={}, type={}", exception.status(), exception.getClass().getSimpleName());
            throw new DeliveryException(DeliveryErrorCode.AUTH_SERVICE_UNAVAILABLE, exception);
        }
    }

    /**
     * 마스킹된 표시 이름. 없는 회원은 맵에 없다. 이름이 없으면 값은 null.
     *
     * @throws DeliveryException {@code AUTH_SERVICE_UNAVAILABLE} — 봉투 · 본문 · 아이디가 계약과 다르다
     */
    public Map<Long, String> toMaskedNames(Response<MemberDisplayNameInternalResponse> response) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success()
            || response.dataBody() == null || response.dataBody().members() == null) {
            log.error("auth-service member display name response is not a success envelope. resultCode={}",
                response == null || response.dataHeader() == null ? null : loggable(response.dataHeader().resultCode()));
            throw new DeliveryException(DeliveryErrorCode.AUTH_SERVICE_UNAVAILABLE);
        }
        try {
            Map<Long, String> names = new HashMap<>();
            for (MemberDisplayNameInternalItem item : response.dataBody().members()) {
                if (item == null) {
                    throw new IllegalArgumentException("members 원소가 없습니다");
                }
                long memberId = parseMemberId(item.memberId());
                names.putIfAbsent(memberId, blankToNull(item.displayName()));
            }
            return names;
        } catch (RuntimeException exception) {
            log.error("auth-service member display name response violates contract. type={}", exception.getClass().getSimpleName());
            throw new DeliveryException(DeliveryErrorCode.AUTH_SERVICE_UNAVAILABLE, exception);
        }
    }

    private static long parseMemberId(String memberId) {
        if (memberId == null || memberId.isEmpty() || memberId.charAt(0) == '0' || memberId.charAt(0) == '-') {
            throw new IllegalArgumentException("memberId 형식이 아닙니다");
        }
        try {
            return Long.parseLong(memberId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("memberId 형식이 아닙니다");
        }
    }

    private static String blankToNull(String displayName) {
        return displayName == null || displayName.isBlank() ? null : displayName;
    }

    /** 코드 모양일 때만 그대로 남긴다. 원격의 임의 문자열을 로그에 넣지 않는다. */
    private static String loggable(String resultCode) {
        if (resultCode == null) {
            return null;
        }
        return resultCode.length() <= MAX_LOGGED_LENGTH && RESULT_CODE_SHAPE.matcher(resultCode).matches() ? resultCode : "<unrecognized>";
    }
}
