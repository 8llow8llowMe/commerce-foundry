package com.commercefoundry.domainlayer.order.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.request.OrderStatusBulkChangeRequest;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.request.OrderStatusChangeRequest;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.OrderAdminResponse;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.OrderStatusBulkChangeResponse;
import com.commercefoundry.domainlayer.order.application.command.OrderAdminSearchCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.application.port.in.OrderAdminUseCase;
import com.commercefoundry.domainlayer.order.application.model.OrderStatusFilter;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 주문 관리 (#17 PR-1) — 목록 · 상세 · 상태 변경 · 일괄 상태 변경. 바꾼 관리자는 access 토큰의 memberId 로만 남긴다 — 본문으로 받지 않는다.
 * 어떤 상태로 바꿀 수 있는지는 상세의 {@code nextStatuses} 다 ({@code order-flow-guide.md} §1 · §6).
 *
 * <p>파라미터 검증은 Spring MVC 의 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "[어드민] 주문 관리", description = "주문 목록 · 상세(상태 이력 포함) · 상태 변경")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/orders")
public class OrderAdminController {

    private final OrderAdminUseCase orderAdminUseCase;

    @Operation(
        summary = "주문 목록 조회",
        description = "최신 주문부터 페이지 단위로 읽는다. 상태 · 주문 기간([from, to), UTC)으로 거를 수 있고 없으면 전체다. 받는 분 이름은 가린다. "
            + "기간 시작이 끝보다 늦으면 400(ORDER_146). 결제수단 필터는 결제 연동(#14) 뒤에 더한다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<PageResponse<OrderAdminItem>>> getOrders(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "주문 상태. 없으면 전체", example = "PAID") @RequestParam(required = false) OrderStatusFilter status,
        @Parameter(description = "주문 시각 시작(포함, UTC ISO-8601). 없으면 처음부터", example = "2026-10-01T00:00:00Z") @RequestParam(required = false) Instant from,
        @Parameter(description = "주문 시각 끝(제외, UTC ISO-8601). 없으면 지금까지", example = "2026-11-01T00:00:00Z") @RequestParam(required = false) Instant to,
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @PositiveOrZero(message = OrderValidationMessage.PAGE_MIN) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = OrderValidationMessage.SIZE_RANGE) @Max(value = 100, message = OrderValidationMessage.SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok().body(Response.success(orderAdminUseCase.getOrders(new OrderAdminSearchCommand(status, from, to, page, size), admin.memberId())));
    }

    @Operation(
        summary = "주문 상세 조회",
        description = "항목 · 금액 · 배송지 · 상태 이력과, 이 화면에서 지금 바꿀 수 있는 다음 상태(nextStatuses)를 본다. 배송지의 받는 분 · 연락처 · 상세 주소 · "
            + "요청 사항은 가린다. 없는 주문이면 404(ORDER_001)."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{orderId}")
    public ResponseEntity<Response<OrderAdminResponse>> getOrder(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "주문 아이디") @PathVariable @Positive(message = OrderValidationMessage.ORDER_ID_POSITIVE) long orderId
    ) {
        return ResponseEntity.ok().body(Response.success(orderAdminUseCase.getOrder(orderId, admin.memberId())));
    }

    @Operation(
        summary = "주문 상태 변경",
        description = "상세의 nextStatuses 중 하나로 바꾸고 바뀐 상세를 돌려준다. 이 화면에서 바꾸는 단계는 결제 완료 → 제작 중(맞춤 제작 상품이 있을 때) · 상품 준비중, "
            + "제작 중 → 상품 준비중, 배송 중 → 배송 완료다. 이미 그 상태면 바꾸지 않고 200 이다. "
            + "허용되지 않은 전이 409(ORDER_020), 결제 · 출고 · 취소 · 반품처럼 다른 흐름이 바꾸는 상태 409(ORDER_023), 맞춤 제작 상품이 없는 주문의 제작 중 409(ORDER_024), "
            + "읽은 뒤 다른 곳(고객 취소 · 결제 · 다른 운영자)이 먼저 바꿈 409(ORDER_025 — 새로고침 후 다시), 없는 주문 404(ORDER_001)."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @AdminAudited(action = "ORDER_STATUS_CHANGE")
    @PatchMapping("/{orderId}/status")
    public ResponseEntity<Response<OrderAdminResponse>> changeStatus(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "주문 아이디") @PathVariable @Positive(message = OrderValidationMessage.ORDER_ID_POSITIVE) long orderId,
        @RequestBody @Valid OrderStatusChangeRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(orderAdminUseCase.changeStatus(orderId, request.toCommand(), admin.memberId())));
    }

    @Operation(
        summary = "주문 상태 일괄 변경",
        description = "여러 주문(1~100개)을 같은 상태로 바꾼다 — 예: 결제 완료 주문들을 상품 준비중으로. 주문마다 따로 바꿔 일부가 실패해도 200 이고, results 에 "
            + "주문별 결과(CHANGED · UNCHANGED · FAILED)와 실패 사유가 요청 순서로 담긴다. 실패한 주문만 골라 다시 보내면 된다(이미 바뀐 주문은 UNCHANGED). "
            + "주문 아이디가 비었거나 100개를 넘거나 겹치면 400(ORDER_149 · ORDER_150)."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @AdminAudited(action = "ORDER_STATUS_CHANGE")
    @PostMapping("/status-changes")
    public ResponseEntity<Response<OrderStatusBulkChangeResponse>> changeStatuses(
        @AuthenticationPrincipal MemberPrincipal admin,
        @RequestBody @Valid OrderStatusBulkChangeRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(orderAdminUseCase.changeStatuses(request.orderIdValues(), request.toCommand(), admin.memberId())));
    }
}
