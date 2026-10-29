package com.commercefoundry.domainlayer.order.adapter.in.web.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.request.OrderCreateRequest;
import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderCreateResponse;
import com.commercefoundry.domainlayer.order.application.port.in.OrderWebUseCase;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 주문 생성 (로그인 전용 — v1 은 비회원 주문을 받지 않는다). 회원은 JWT 주체로만 정한다 — 요청에 memberId 를 받지 않는다 ({@code api-design-guide.md} §5).
 *
 * <p>{@code Idempotency-Key} 는 {@code UUID} 로 바인딩한다 — 형식 오류는 타입 불일치(400 {@code ORDER_198}), 누락은 400 {@code ORDER_199} 다.
 * 헤더에 제약 애노테이션(@Pattern 등)을 달지 않는다 — 달면 본문 검증까지 메서드 검증으로 바뀌어 본문 필드 경로를 잃는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "주문", description = "주문서 금액 미리보기 · 주문 생성")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
public class OrderWebController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final OrderWebUseCase orderWebUseCase;

    @Operation(
        summary = "주문 생성",
        description = "CUSTOMER가 서버 계산 금액으로 재고를 선점하고 결제 대기(PENDING_PAYMENT) 주문을 만든다 — 201. paymentDueAt은 미결제 만료 판정 기준이며 만료 스케줄러는 후속이다. "
            + "Idempotency-Key(UUID) 헤더가 필수다 — 결제 페이지 세션 동안 같은 키를 쓴다. 같은 키 · 같은 본문의 재요청은 같은 주문을 지금 상태로 200 으로 돌려주고, "
            + "다른 본문이면 409(ORDER_019), 처음 요청이 처리 중이면 409(ORDER_018)다. 5xx · 409(ORDER_018) 는 같은 키로 다시 보내고, 그 밖의 4xx 뒤에는 새 키를 쓴다. "
            + "미리보기의 오류에 더해 금액 변경 409(ORDER_010 — expectedTotalAmount), 재고 부족 409(ORDER_016), 주문 접수 실패 500(ORDER_017 · ORDER_021). "
            + "헤더 없음 400(ORDER_199) · UUID 아님 400(ORDER_198)."
    )
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping
    public ResponseEntity<Response<OrderCreateResponse>> create(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "멱등 키 (UUID). 같은 주문의 재시도에는 같은 값을 보낸다", example = "3f2b8c1e-6a4d-4e2f-9b1a-7c5d2e8f0a11")
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) UUID idempotencyKey,
        @RequestBody @Valid OrderCreateRequest request
    ) {
        OrderWebUseCase.Result result = orderWebUseCase.create(member.memberId(), idempotencyKey, request.toCommand());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(Response.success(result.order()));
    }
}
