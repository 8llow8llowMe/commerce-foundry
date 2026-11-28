package com.commercefoundry.domainlayer.order.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.PaymentRestorationObservationInternalResponse;
import com.commercefoundry.domainlayer.order.application.port.in.PaymentRestorationObservationInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 반영 재고 복원 작업 관측 (#808, {@code order-flow-guide.md} §3-6). 호출자는 operations-service 하나다 — 복원 장기 대기 · 사람 확인(HELD)
 * 이상 알림을 위해 주기적으로 읽는다. 건수와 시각만 돌려주며 캐시하지 않는다.
 *
 * <p>서비스 토큰이 없거나 틀린 요청은 internal 보안 체인이 이 컨트롤러에 닿기 전에 401({@code SECURITY_007})로 막는다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/payment-restorations")
public class PaymentRestorationObservationInternalController {

    private final PaymentRestorationObservationInternalUseCase useCase;

    @PreAuthorize("hasAuthority('SERVICE_operations-service')")
    @GetMapping("/observation")
    public ResponseEntity<Response<PaymentRestorationObservationInternalResponse>> observe() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.observe()));
    }
}
