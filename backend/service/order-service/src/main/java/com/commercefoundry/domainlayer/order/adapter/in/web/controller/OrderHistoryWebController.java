package com.commercefoundry.domainlayer.order.adapter.in.web.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.item.OrderSummaryItem;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.request.OrderCancelRequest;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderDetailResponse;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.application.port.in.OrderHistoryWebUseCase;
import com.commercefoundry.persistence.dto.SliceResponse;
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
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 주문 내역 · 상세 · 결제 전 취소 (#16 PR-1). 회원은 토큰 주체이고 남의 주문은 404 다.
 *
 * <p>파라미터 검증은 Spring MVC 의 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "주문 내역", description = "내 주문 목록 · 상세 · 결제 전 취소")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
public class OrderHistoryWebController {

    private final OrderHistoryWebUseCase orderHistoryWebUseCase;

    @Operation(summary = "내 주문 목록", description = "최신순 한 조각(SliceResponse, 전체 건수 없음). 400 ORDER_144(page) · ORDER_145(size 1~100)")
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping
    public ResponseEntity<Response<SliceResponse<OrderSummaryItem>>> getOrders(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @PositiveOrZero(message = OrderValidationMessage.PAGE_MIN) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = OrderValidationMessage.SIZE_RANGE) @Max(value = 100, message = OrderValidationMessage.SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok().body(Response.success(orderHistoryWebUseCase.getOrders(member.memberId(), page, size)));
    }

    @Operation(summary = "내 주문 상세", description = "주문 시점 항목 · 금액 · 배송비 · 배송지. 없거나 남의 주문은 404 ORDER_001")
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/{orderId}")
    public ResponseEntity<Response<OrderDetailResponse>> getOrder(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "주문 아이디") @PathVariable @Positive(message = OrderValidationMessage.ORDER_ID_POSITIVE) long orderId
    ) {
        return ResponseEntity.ok().body(Response.success(orderHistoryWebUseCase.getOrder(member.memberId(), orderId)));
    }

    @Operation(
        summary = "결제 전 주문 취소",
        description = "결제 대기 주문을 취소 완료로 바꾸고 잡아 둔 재고를 푼다 — 200 취소된 상세. 이미 취소됐으면 바꾸지 않고 200. "
            + "결제 완료 · 만료 등은 409 ORDER_020(결제 뒤 취소는 후속). 사유 없음 400 ORDER_154, 모르는 사유 400 ORDER_100, 없거나 남의 주문 404 ORDER_001"
    )
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{orderId}/cancellation")
    public ResponseEntity<Response<OrderDetailResponse>> cancel(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "주문 아이디") @PathVariable @Positive(message = OrderValidationMessage.ORDER_ID_POSITIVE) long orderId,
        @RequestBody @Valid OrderCancelRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(orderHistoryWebUseCase.cancel(member.memberId(), orderId, request.reason())));
    }
}
