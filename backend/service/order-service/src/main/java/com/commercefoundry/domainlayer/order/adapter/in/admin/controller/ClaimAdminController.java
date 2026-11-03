package com.commercefoundry.domainlayer.order.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.ClaimAdminResponse;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.application.model.ClaimAdminFilter;
import com.commercefoundry.domainlayer.order.application.port.in.ClaimAdminUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.principal.MemberPrincipal;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 클레임 조회", description = "반품 접수 목록과 상세 조회")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/claims")
public class ClaimAdminController {
    private final ClaimAdminUseCase useCase;

    @Operation(summary = "클레임 목록 조회", description = "클레임 아이디 내림차순. 현재 RETURN/REQUESTED 접수만 지원하며 예상 환불액 null은 미산정이다. "
        + "page는 0 이상, size는 1~100이며 page×size가 2,147,483,647을 넘으면 조회 전에 400(ORDER_167)이다.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<PageResponse<ClaimAdminItem>>> getClaims(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "주문 아이디. 없으면 전체") @RequestParam(required = false)
        @Positive(message = OrderValidationMessage.ORDER_ID_POSITIVE) Long orderId,
        @Parameter(description = "클레임 유형. 없으면 전체") @RequestParam(required = false) ClaimAdminFilter.Type type,
        @Parameter(description = "클레임 상태. 없으면 전체") @RequestParam(required = false) ClaimAdminFilter.Status status,
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @PositiveOrZero(message = OrderValidationMessage.PAGE_MIN) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = OrderValidationMessage.SIZE_RANGE) @Max(value = 100, message = OrderValidationMessage.SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok(Response.success(useCase.getClaims(new ClaimAdminFilter(orderId, type, status, page, size), admin.memberId())));
    }

    @Operation(summary = "클레임 상세 조회", description = "접수 헤더와 항목별 수량/사유. 없는 접수는 404(ORDER_032), 양수가 아닌 아이디는 400(ORDER_166).")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{claimId}")
    public ResponseEntity<Response<ClaimAdminResponse>> getClaim(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "클레임 아이디") @PathVariable @Positive(message = OrderValidationMessage.CLAIM_ID_POSITIVE) long claimId
    ) {
        return ResponseEntity.ok(Response.success(useCase.getClaim(claimId, admin.memberId())));
    }
}
