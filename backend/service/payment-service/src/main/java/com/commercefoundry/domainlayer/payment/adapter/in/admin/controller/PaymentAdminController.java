package com.commercefoundry.domainlayer.payment.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.payment.adapter.in.admin.dto.item.PaymentAdminItem;
import com.commercefoundry.domainlayer.payment.application.command.PaymentAdminSearchCommand;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentValidationMessage;
import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminStatusFilter;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentAdminUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 결제 관리", description = "결제 상태 · 주문 회신 확인을 위한 읽기 전용 목록")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/payments")
public class PaymentAdminController {
    private final PaymentAdminUseCase paymentAdminUseCase;

    @Operation(summary = "결제 목록 조회", description = "결제 시도를 아이디 내림차순으로 조회한다. REFUND_FAILED는 PG 취소 확정 거절 이력 필터이며 실제 결제 상태는 그대로 반환한다.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<PageResponse<PaymentAdminItem>>> search(
        @Parameter(description = "결제 상태. REFUND_FAILED는 취소거절 이력(REJECTED)이 있는 결제이며 후속 성공 뒤에도 포함한다. 미지정이면 전체")
        @RequestParam(required = false) PaymentAdminStatusFilter status,
        @Parameter(description = "주문 아이디. 미지정이면 전체") @RequestParam(required = false)
        @Positive(message = PaymentValidationMessage.ORDER_ID_POSITIVE) Long orderId,
        @Parameter(description = "주문 성공 회신 수신 여부. false 자체가 재시도 지시는 아니다") @RequestParam(required = false) Boolean orderSynced,
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @PositiveOrZero(message = PaymentValidationMessage.PAGE_MIN) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = PaymentValidationMessage.SIZE_RANGE) @Max(value = 100, message = PaymentValidationMessage.SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok(Response.success(paymentAdminUseCase.search(new PaymentAdminSearchCommand(status, orderId, orderSynced, page, size))));
    }
}
