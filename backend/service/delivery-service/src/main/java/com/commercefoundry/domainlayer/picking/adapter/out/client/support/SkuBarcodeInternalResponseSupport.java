package com.commercefoundry.domainlayer.picking.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.picking.adapter.out.client.feign.SkuBarcodeClient;
import com.commercefoundry.domainlayer.picking.application.exception.PickingErrorCode;
import com.commercefoundry.domainlayer.picking.application.exception.PickingException;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SkuBarcodeInternalResponseSupport {

    private static final Pattern SKU_ID = Pattern.compile("[1-9][0-9]{0,18}");

    private final CircuitBreaker circuitBreaker;

    public SkuBarcodeInternalResponseSupport(CircuitBreakerRegistry registry) {
        this.circuitBreaker = registry.circuitBreaker("product-service");
    }

    public Map<String, Long> read(Set<String> barcodes, Supplier<Response<SkuBarcodeClient.Resolved>> remoteCall) {
        try {
            return circuitBreaker.executeSupplier(() -> toSkuIds(barcodes, remoteCall.get()));
        } catch (FeignException | CallNotPermittedException | IllegalArgumentException exception) {
            // 스캔 원문, 서비스 토큰, 원격 응답을 로그나 공개 응답에 포함하지 않는다.
            log.warn("product-service barcode lookup failed. status={}, type={}",
                exception instanceof FeignException feign ? feign.status() : null, exception.getClass().getSimpleName());
            throw new PickingException(PickingErrorCode.BARCODE_LOOKUP_UNAVAILABLE);
        }
    }

    private static Map<String, Long> toSkuIds(Set<String> requested, Response<SkuBarcodeClient.Resolved> response) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null
            || response.dataBody().skuIdsByBarcode() == null) {
            throw new IllegalArgumentException("success envelope required");
        }
        var result = new HashMap<String, Long>();
        var seen = new HashSet<Long>();
        response.dataBody().skuIdsByBarcode().forEach((barcode, skuId) -> {
            if (!requested.contains(barcode) || skuId == null || !SKU_ID.matcher(skuId).matches()) {
                throw new IllegalArgumentException("unexpected barcode or SKU ID");
            }
            long parsed = Long.parseLong(skuId);
            if (!seen.add(parsed)) {
                throw new IllegalArgumentException("multiple barcodes for a SKU");
            }
            result.put(barcode, parsed);
        });
        return Map.copyOf(result);
    }
}
