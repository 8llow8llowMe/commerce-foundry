package com.commercefoundry.domainlayer.picking.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.picking.adapter.out.client.feign.dto.OrderPickingSnapshotInternalResponse;
import com.commercefoundry.domainlayer.picking.application.exception.PickingErrorCode;
import com.commercefoundry.domainlayer.picking.application.exception.PickingException;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSnapshot;
import com.commercefoundry.global.support.RemoteResultCodes;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * order-service 피킹 스냅숏 조회의 서킷 · 예외 변환 · 응답 변환 ({@code coding-conventions.md} §10). {@code FeignException} 과 원격 응답 모양을 이 밖으로 내보내지 않는다.
 *
 * <ul>
 *   <li>서킷 인스턴스는 {@value #CIRCUIT_BREAKER} — 송장 출고 반영과 같은 대상이라 같은 인스턴스를 쓴다({@code application.yml}). 4xx 는 서킷 실패로 세지 않는다.</li>
 *   <li>조회라 업무 거절이 없다 — 요청은 이미 검증했으므로 4xx(400 · 401 · 403 등) · 5xx · 타임아웃 · 전송 실패 · 서킷 열림 · 계약과 다른 응답을 모두
 *       {@code SNAPSHOT_UNAVAILABLE}(503) 하나로 바꾼다. 운영자에게는 업무 문구만 나가고 원격 메시지는 나가지 않는다.</li>
 *   <li>로그에는 HTTP 상태 · resultCode(코드 모양일 때만) · 예외 타입만 남긴다. 응답 본문 · 메시지 · throwable 은 남기지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class OrderPickingInternalResponseSupport {

    static final String CIRCUIT_BREAKER = "order-service";

    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern ORDER_STATUS_CODE = Pattern.compile("[A-Z][A-Z_]{0,31}");
    private static final Pattern COUNTRY_CODE = Pattern.compile("[A-Z]{2}");

    private final CircuitBreaker circuitBreaker;

    public OrderPickingInternalResponseSupport(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
    }

    /**
     * @param requested 보낸 주문 아이디 — 응답에는 이 중 있는 것만 한 번씩 와야 한다
     * @throws PickingException {@code SNAPSHOT_UNAVAILABLE}
     */
    public List<PickingSnapshot> read(List<Long> requested, Supplier<Response<List<OrderPickingSnapshotInternalResponse>>> remoteCall) {
        Response<List<OrderPickingSnapshotInternalResponse>> response;
        try {
            response = circuitBreaker.executeSupplier(remoteCall);
        } catch (FeignException | CallNotPermittedException exception) {
            log.warn("order-service picking snapshot call failed. status={}, type={}",
                exception instanceof FeignException feignException ? feignException.status() : null, exception.getClass().getSimpleName());
            throw new PickingException(PickingErrorCode.SNAPSHOT_UNAVAILABLE, exception);
        }
        try {
            return toSnapshots(requested, response);
        } catch (RuntimeException exception) {
            log.error("order-service picking snapshot response violates contract. type={}, resultCode={}", exception.getClass().getSimpleName(),
                response == null || response.dataHeader() == null ? null : RemoteResultCodes.loggable(response.dataHeader().resultCode()));
            throw new PickingException(PickingErrorCode.SNAPSHOT_UNAVAILABLE, exception);
        }
    }

    private static List<PickingSnapshot> toSnapshots(List<Long> requested, Response<List<OrderPickingSnapshotInternalResponse>> response) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null) {
            throw new IllegalArgumentException("success envelope required");
        }
        Set<Long> seen = new HashSet<>();
        return response.dataBody().stream().map(order -> {
            if (order == null) {
                throw new IllegalArgumentException("snapshot required");
            }
            long orderId = positiveId(order.orderId());
            if (!requested.contains(orderId) || !seen.add(orderId)) {
                throw new IllegalArgumentException("unrequested or duplicated order");
            }
            if (order.status() == null || !ORDER_STATUS_CODE.matcher(order.status()).matches()) {
                throw new IllegalArgumentException("status shape");
            }
            if (order.countryCode() != null && !COUNTRY_CODE.matcher(order.countryCode()).matches()) {
                throw new IllegalArgumentException("country shape");
            }
            if (order.lines() == null) {
                throw new IllegalArgumentException("lines required");
            }
            return new PickingSnapshot(orderId, order.status(), order.countryCode(), order.lines().stream().map(line -> {
                if (line == null || line.quantity() == null || line.customOrder() == null) {
                    throw new IllegalArgumentException("line shape");
                }
                return new PickingSnapshot.Line(positiveId(line.skuId()), line.quantity(), line.customOrder());
            }).toList());
        }).toList();
    }

    private static long positiveId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("id shape");
        }
        return Long.parseLong(id);
    }
}
