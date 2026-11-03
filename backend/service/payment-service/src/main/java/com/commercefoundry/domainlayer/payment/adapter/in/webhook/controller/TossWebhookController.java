package com.commercefoundry.domainlayer.payment.adapter.in.webhook.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.payment.adapter.in.webhook.dto.request.TossWebhookRequest;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentWebhookUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "결제 웹훅", description = "외부 결제 상태 변경 알림 수신")
@RestController
@RequiredArgsConstructor
public class TossWebhookController {
    private final PaymentWebhookUseCase useCase;
    @Operation(summary = "토스 결제 상태 알림 수신", description = "로컬 키 바인딩 확인 후 영속 수신함 커밋. 실제 상태는 별도 PG 조회로 확인한다")
    @PreAuthorize("permitAll()")
    @PostMapping("/api/v1/payments/webhooks/toss")
    public ResponseEntity<Response<Void>> receive(@Valid @RequestBody TossWebhookRequest request) {
        useCase.receive(request.toCommand());
        return ResponseEntity.ok(Response.success(null));
    }
}
