package com.commercefoundry.domainlayer.order.adapter.in.web.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.request.OrderReturnRequest;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderReturnResponse;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.application.port.in.OrderReturnWebUseCase;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "주문 반품", description = "고객 부분 반품 접수")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders/{orderId}/returns")
public class OrderReturnWebController {
    private final OrderReturnWebUseCase orderReturnWebUseCase;

    @Operation(summary = "부분 반품 접수", description = "CUSTOMER 본인 주문의 DELIVERED·RETURN_REQUESTED 상태에서 부분 반품을 접수한다. "
        + "신규 201, 같은 주문·키·정렬한 항목 본문 재생 200이다. 키는 완전한 UUID 36자이며 대소문자는 구분하지 않는다. "
        + "환불 실행·배송비 계산·재고 복원은 하지 않으며 expectedRefund는 미산정 null이다. "
        + "없는 주문·타인 주문 404(ORDER_001), 상태 거절 409(ORDER_020), 동시 변경 409(ORDER_025), 항목 오류 400(ORDER_028), "
        + "잔여 수량 초과 409(ORDER_029), 같은 키 다른 본문 409(ORDER_030), 저장 실패 500(ORDER_021), 헤더 형식 400(ORDER_198), 누락 400(ORDER_199).")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping
    public ResponseEntity<Response<OrderReturnResponse>> request(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "주문 아이디", example = "4611686018427387904")
        @PathVariable @Positive(message = OrderValidationMessage.ORDER_ID_POSITIVE) long orderId,
        @Parameter(description = "주문별 멱등 접수 키 (완전한 UUID 36자)", example = "3f2b8c1e-6a4d-4e2f-9b1a-7c5d2e8f0a11")
        @RequestHeader("Idempotency-Key")
        @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", message = OrderValidationMessage.IDEMPOTENCY_KEY_FORMAT)
        String requestKey,
        @RequestBody @Valid OrderReturnRequest request
    ) {
        var result = orderReturnWebUseCase.request(member.memberId(), orderId, UUID.fromString(requestKey), request.toCommand());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(Response.success(result.claim()));
    }
}
