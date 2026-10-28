package com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.response.ShippingPolicyFactInternalResponse;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryValidationMessage;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.OnboardingFactInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 운영자 온보딩 사실 (#357) — operations-service 가 운영자의 업무 완료(tasks)를 판정할 때 읽는다. 배송 업무는 "국내 배송비 정책 저장" 이다.
 *
 * <p>호출자는 operations-service 하나다. 사용자 토큰은 internal 로 오지 않으므로 운영자는 호출 서비스가 access 토큰으로 확인한 {@code memberId} 를
 * 쿼리 파라미터로 받는다 ({@code security-guide.md} §9-4). 서비스 토큰이 없거나 틀린 요청은 internal 보안 체인이 이 컨트롤러에 닿기 전에
 * 401({@code SECURITY_007})로 막는다. 인증을 통과한 요청에서는 메서드 검증이 {@code @PreAuthorize} 보다 먼저 돈다. 클래스에 {@code @Validated} 를 달지 않는다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/onboarding-facts")
public class OnboardingFactInternalController {

    private final OnboardingFactInternalUseCase onboardingFactInternalUseCase;

    @PreAuthorize("hasAuthority('SERVICE_operations-service')")
    @GetMapping("/shipping-policy")
    public ResponseEntity<Response<ShippingPolicyFactInternalResponse>> getShippingPolicyFact(
        @RequestParam @Positive(message = DeliveryValidationMessage.MEMBER_ID_POSITIVE) long memberId
    ) {
        return ResponseEntity.ok().body(Response.success(onboardingFactInternalUseCase.getShippingPolicyFact(memberId)));
    }
}
