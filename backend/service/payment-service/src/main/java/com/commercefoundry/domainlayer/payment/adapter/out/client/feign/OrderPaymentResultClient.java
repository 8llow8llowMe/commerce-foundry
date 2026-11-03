package com.commercefoundry.domainlayer.payment.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.payment.adapter.out.client.feign.dto.OrderPaymentResultInternalResponse;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 최초 반영 결과 조회. 서비스 JWT 및 공용 connect 2초 / read 3초 설정을 사용한다.
 * 어댑터 · Feign 재시도는 없으며 하부 HTTP 전송 계층은 안전한 GET 연결을 재접속할 수 있다.
 */
@FeignClient(
    name = "${feign-client.target-services.order-service:order-service}",
    contextId = "orderPaymentResultClient",
    configuration = InternalFeignClientConfiguration.class)
public interface OrderPaymentResultClient {

    @GetMapping("/internal/v1/order-payments/{paymentId}")
    Response<OrderPaymentResultInternalResponse> getResult(@PathVariable("paymentId") long paymentId);
}
