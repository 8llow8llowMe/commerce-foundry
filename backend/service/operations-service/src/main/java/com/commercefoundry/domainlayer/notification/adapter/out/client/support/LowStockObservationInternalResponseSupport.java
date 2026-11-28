package com.commercefoundry.domainlayer.notification.adapter.out.client.support;

import com.commercefoundry.domainlayer.notification.application.exception.NotificationErrorCode;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationException;
import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy;
import com.commercefoundry.global.support.RemoteResultCodes;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * product 품절 임박 커서 조회의 서킷 · 예외 변환 · 응답 계약 검증(#229).
 *
 * <p>서킷 {@code product-service} 는 다른 product 호출과 공유한다. 서킷 안에서는 원격 호출만 하고 계약 검증은 서킷 밖에서 한다 —
 * 우리 쪽 해석 문제가 공유 서킷을 열어 다른 product 업무까지 막지 않게. 실패는 모두 {@code NOTIFICATION_053} 이며 원격 예외 · 본문 · URL 은
 * 원인으로 남기지 않는다. 응답에서는 {@code observedAt} · {@code contents[].skuId} · {@code hasNext} · {@code nextAfterSkuId} 만 읽는다.
 */
@Slf4j
@Component
public class LowStockObservationInternalResponseSupport {
    static final String CIRCUIT_BREAKER = "product-service";
    private static final Pattern SKU_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private final CircuitBreaker circuitBreaker;

    public LowStockObservationInternalResponseSupport(CircuitBreakerRegistry registry) {
        this.circuitBreaker = registry.circuitBreaker(CIRCUIT_BREAKER);
    }

    /** 호출 전 요청 값 확인. 우리 쪽 오류라 원격을 부르지 않고 바로 실패한다. */
    public void requireRequest(long afterSkuId, int size) {
        if (afterSkuId < 0 || size < 1 || size > LowStockAlertPolicy.PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid low stock observation request");
        }
    }

    public JsonNode call(Supplier<JsonNode> remoteCall) {
        try {
            return circuitBreaker.executeSupplier(remoteCall);
        } catch (CallNotPermittedException ignored) {
            log.warn("low_stock_observation_failed reason=circuit_open");
        } catch (FeignException.FeignClientException exception) {
            log.error("low_stock_observation_failed reason=rejected status={}", exception.status());
        } catch (FeignException exception) {
            log.warn("low_stock_observation_failed reason=remote status={} type={}", exception.status(), exception.getClass().getSimpleName());
        } catch (RuntimeException exception) {
            log.warn("low_stock_observation_failed reason=remote type={}", exception.getClass().getSimpleName());
        }
        throw new NotificationException(NotificationErrorCode.LOW_STOCK_OBSERVATION_UNAVAILABLE);
    }

    public LowStockAlertPolicy.Page toPage(JsonNode root, long afterSkuId, int size) {
        try {
            return normalize(root, afterSkuId, size);
        } catch (RuntimeException ignored) {
            // 본문 · 원인 예외에는 상품 정보나 URL 이 실릴 수 있어 남기지 않는다.
            log.error("low_stock_observation_failed reason=contract resultCode={}", resultCode(root));
            throw new NotificationException(NotificationErrorCode.LOW_STOCK_OBSERVATION_UNAVAILABLE);
        }
    }

    private LowStockAlertPolicy.Page normalize(JsonNode root, long afterSkuId, int size) {
        object(root);
        var header = root.path("dataHeader");
        object(header);
        var success = header.path("success");
        if (!success.isBoolean() || !success.booleanValue()) {
            throw new IllegalArgumentException("envelope");
        }
        var body = root.path("dataBody");
        object(body);
        var observedAt = body.path("observedAt");
        if (!observedAt.isString()) {
            throw new IllegalArgumentException("observedAt");
        }
        var contents = body.path("contents");
        if (!contents.isArray() || contents.size() > size) {
            throw new IllegalArgumentException("contents");
        }
        var skuIds = new ArrayList<Long>(contents.size());
        for (int index = 0; index < contents.size(); index++) {
            var item = contents.get(index);
            object(item);
            skuIds.add(skuId(item.path("skuId")));
        }
        var hasNext = body.path("hasNext");
        if (!hasNext.isBoolean()) {
            throw new IllegalArgumentException("hasNext");
        }
        var next = body.path("nextAfterSkuId");
        Long nextAfterSkuId;
        if (hasNext.booleanValue()) {
            nextAfterSkuId = skuId(next);
        } else if (next.isMissingNode() || next.isNull()) {
            nextAfterSkuId = null;
        } else {
            throw new IllegalArgumentException("nextAfterSkuId");
        }
        // 요청 커서를 함께 실어 Page 가 "커서 뒤 오름차순"을, Scan.of 가 페이지 간 커서 연속성을 검증한다.
        return new LowStockAlertPolicy.Page(afterSkuId, Instant.parse(observedAt.stringValue()), skuIds, hasNext.booleanValue(), nextAfterSkuId);
    }

    private static long skuId(JsonNode node) {
        if (!node.isString() || !SKU_ID.matcher(node.stringValue()).matches()) {
            throw new IllegalArgumentException("skuId");
        }
        return Long.parseLong(node.stringValue());
    }

    private static void object(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("object");
        }
    }

    private static String resultCode(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        var code = root.path("dataHeader").path("resultCode");
        return code.isString() ? RemoteResultCodes.loggable(code.stringValue()) : null;
    }
}
