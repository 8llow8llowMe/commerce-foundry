package com.commercefoundry.domainlayer.catalog.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.catalog.adapter.out.client.feign.dto.ProductSkuInternalResponse;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * product-service internal SKU 벌크 조회. 서비스 토큰을 싣는다({@link InternalFeignClientConfiguration}) — 사용자 {@code Authorization} 은 넘기지 않는다
 * ({@code security-guide.md} §9-5). {@code name} 은 대상의 Eureka 서비스 ID 이자 서비스 토큰의 {@code aud} 다.
 *
 * <p>직접 부르지 않는다 — 서킷 · 예외 변환은 {@code ProductInternalResponseSupport} 가 감싸고, application 에는 {@code SkuQueryPort} 로만 나간다.
 */
@FeignClient(
    name = "${feign-client.target-services.product-service:product-service}",
    contextId = "productSkuClient",
    configuration = InternalFeignClientConfiguration.class)
public interface ProductSkuClient {

    /** 한 번에 보낼 수 있는 아이디 수 — product-service 벌크 조회 상한 ({@code api-design-guide.md} §1-3). 넘으면 대상이 400 으로 거절한다. */
    int MAX_IDS_PER_CALL = 100;

    /**
     * @param ids 1~{@value #MAX_IDS_PER_CALL}개. {@code ids=1&ids=2} 로 보낸다
     */
    @GetMapping("/internal/v1/skus")
    Response<ProductSkuInternalResponse> getSkus(@RequestParam("ids") List<Long> ids);
}
