package com.commercefoundry.domainlayer.order.adapter.in.web.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.request.OrderQuoteRequest;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderQuoteResponse;
import com.commercefoundry.domainlayer.order.application.port.in.OrderQuoteWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 주문서 금액 미리보기 (로그인 전용 — v1 은 비회원 주문을 받지 않는다). 금액은 받지 않는다 — 서버가 주문 생성과 같은 코드로 계산한다
 * ({@code order-flow-guide.md} §2). 본문 검증은 {@code @Valid} 가 한다. 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "주문", description = "주문서 금액 미리보기 · 주문 생성")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
public class OrderQuoteWebController {

    private final OrderQuoteWebUseCase orderQuoteWebUseCase;

    @Operation(
        summary = "주문서 금액 미리보기",
        description = "저장하지 않는 계산이다 — 상품 현재 판매가 × 수량 합계와 배송비(delivery-service 정책)로 결제 금액을 낸다. 주문 생성과 같은 계산이다. "
            + "없는 SKU 404(ORDER_003), 품절 · 판매 종료 409(ORDER_004), 맞춤 제작이 아닌데 입력 400(ORDER_005), 상품별 수량 상한 초과 409(ORDER_006), "
            + "통화 혼합 409(ORDER_007), 해외 배송 정보(무게 · HS 코드) 없는 상품 409(ORDER_008), 배송비 정책 없음 409(ORDER_012), 배송할 수 없는 국가 400(ORDER_013), "
            + "통화 · 배송지 불일치 400(ORDER_014), 무게 초과 400(ORDER_015), 상품 · 배송 서비스 장애 503(ORDER_002 · ORDER_011)."
    )
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/quotes")
    public ResponseEntity<Response<OrderQuoteResponse>> quote(@RequestBody @Valid OrderQuoteRequest request) {
        return ResponseEntity.ok().body(Response.success(orderQuoteWebUseCase.quote(request.toCommand())));
    }
}
