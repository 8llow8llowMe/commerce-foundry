package com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.request.ShippingFeeQuoteInternalRequest;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.response.ShippingFeeQuoteInternalResponse;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.ShippingFeeInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 배송비 견적 — 주문서 금액 미리 계산 · 주문 생성이 배송비를 받는다. 저장하지 않는 계산이라 {@code POST} 지만 200 이다
 * ({@code api-design-guide.md} §1-1 의 {@code quotes} 리터럴).
 *
 * <p>계약: {@code orderAmount} 는 할인 후 상품 합계(배송비 제외)다. 계산 주체는 order-service 이고, 여기서는 무료배송 기준과 비교만 한다.
 * 정책이 없으면 0원으로 가정하지 않고 409({@code DELIVERY_001})로 거절한다.
 *
 * <p>호출자는 order-service 하나다. 서비스 토큰이 없거나 틀린 요청은 internal 보안 체인이 이 컨트롤러에 닿기 전에 401({@code SECURITY_007})로 막는다
 * ({@code security-guide.md} §9). 인증을 통과한 요청에서는 본문 검증이 {@code @PreAuthorize}(허용 호출자)보다 먼저 돈다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/shipping-fees")
public class ShippingFeeInternalController {

    private final ShippingFeeInternalUseCase shippingFeeInternalUseCase;

    @PreAuthorize("hasAuthority('SERVICE_order-service')")
    @PostMapping("/quotes")
    public ResponseEntity<Response<ShippingFeeQuoteInternalResponse>> quote(@Valid @RequestBody ShippingFeeQuoteInternalRequest request) {
        return ResponseEntity.ok().body(Response.success(shippingFeeInternalUseCase.quote(request.toCommand())));
    }
}
