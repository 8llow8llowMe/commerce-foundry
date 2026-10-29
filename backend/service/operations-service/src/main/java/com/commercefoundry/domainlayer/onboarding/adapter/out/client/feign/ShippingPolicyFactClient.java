package com.commercefoundry.domainlayer.onboarding.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.onboarding.adapter.out.client.feign.dto.ShippingPolicyFactInternalResponse;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * delivery-service internal 운영자 온보딩 사실 — 국내 배송비 정책 저장 (#357 PR-B1). 서비스 토큰을 싣는다({@link InternalFeignClientConfiguration}) —
 * 사용자 {@code Authorization} 은 넘기지 않는다({@code security-guide.md} §9-5). {@code name} 은 대상의 Eureka 서비스 ID 이자 서비스 토큰의 {@code aud} 다.
 * delivery 는 이 엔드포인트의 허용 호출자로 {@code SERVICE_operations-service} 하나를 선언한다.
 *
 * <p>직접 부르지 않는다 — 서킷 · 예외 변환은 {@code ShippingPolicyFactInternalResponseSupport} 가 감싸고, application 에는
 * {@code ShippingPolicyFactQueryPort} 로만 나간다.
 */
@FeignClient(
    name = "${feign-client.target-services.delivery-service:delivery-service}",
    contextId = "shippingPolicyFactClient",
    configuration = InternalFeignClientConfiguration.class)
public interface ShippingPolicyFactClient {

    /**
     * @param memberId access 토큰으로 확인한 운영자. 요청 파라미터 · 본문에서 받은 값을 넣지 않는다
     */
    @GetMapping("/internal/v1/onboarding-facts/shipping-policy")
    Response<ShippingPolicyFactInternalResponse> getShippingPolicyFact(@RequestParam("memberId") long memberId);
}
