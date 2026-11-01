package com.commercefoundry.domainlayer.payment.adapter.in.web.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.request.PaymentConfirmRequest;
import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.request.PaymentPrepareRequest;
import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.response.PaymentPrepareResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.response.PaymentResponse;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentWebUseCase;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 결제 — 준비 · 승인 · 상태 조회 (CUSTOMER 전용). 회원은 JWT 주체로만 정한다 — 요청에 memberId 를 받지 않는다 ({@code api-design-guide.md} §5).
 *
 * <p>승인의 {@code Idempotency-Key} 는 {@code UUID} 로 바인딩한다 — 누락은 400 {@code PAYMENT_113}, UUID 아님은 400 {@code PAYMENT_114} 다
 * ({@code PaymentExceptionHandler}). 헤더에 제약 애노테이션을 달지 않는다 — 달면 본문 검증까지 메서드 검증으로 바뀌어 본문 필드 경로를 잃는다
 * ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "결제", description = "결제 준비 · 결제 승인 · 결제 상태 조회")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/payments")
public class PaymentWebController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final PaymentWebUseCase paymentWebUseCase;

    @Operation(
        summary = "결제 준비",
        description = "결제 대기(PENDING_PAYMENT) 주문의 결제 시도를 만든다 — 금액 · 주문명은 서버가 주문에서 가져온다. 새로 만들면 201, 이미 있는 결제 시도(같은 금액의 결제 대기 · "
            + "승인 중 · 확인 중 · 완료)면 200 으로 그 시도를 돌려준다. 주문 금액이 바뀌었으면 이전 결제 대기를 버리고 새로 만든다(201). "
            + "응답의 pgOrderId · amount · orderName 으로 결제위젯을 띄운다. 주문 없음 404(PAYMENT_002), 결제할 수 없는 주문 409(PAYMENT_003), "
            + "결제 기한 임박 · 지남 409(PAYMENT_004), 원화가 아닌 주문 409(PAYMENT_005), 요청 겹침 409(PAYMENT_012 — 잠시 뒤 재시도), 주문 정보 일시 장애 503(PAYMENT_011)."
    )
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping
    public ResponseEntity<Response<PaymentPrepareResponse>> prepare(
        @AuthenticationPrincipal MemberPrincipal member,
        @RequestBody @Valid PaymentPrepareRequest request
    ) {
        PaymentWebUseCase.PrepareResult result = paymentWebUseCase.prepare(member.memberId(), request.toCommand());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(Response.success(result.payment()));
    }

    @Operation(
        summary = "결제 승인",
        description = "결제위젯 successUrl 의 paymentKey · orderId(= pgOrderId) · amount 로 결제를 승인한다. Idempotency-Key(UUID) 헤더가 필수다 — 같은 승인 시도의 재전송에는 "
            + "같은 키를 쓴다. 결과는 200 으로 결제 완료(DONE) · 결제 실패(FAILED, failure 에 사유) · 결제 확인 중(UNKNOWN — 몇 초 뒤 상태 조회)이다. "
            + "같은 키 · 같은 본문의 재요청은 처음 결과를 그대로 돌려준다(저장 없음). 결제사에 연결하지 못하면 503(PAYMENT_010, Retry-After 초) — 청구는 없고 같은 본문으로 "
            + "다시 보내면 된다(키는 같아도 새로 만들어도 된다). 승인 처리 중 409(PAYMENT_007 — 상태 조회), 끝난 결제 시도 409(PAYMENT_006 — 결제 준비부터 다시), "
            + "금액 불일치 400(PAYMENT_008), 결제키 불일치 400(PAYMENT_009), 같은 키로 다른 결제 409(PAYMENT_014), 결제 없음 404(PAYMENT_001), "
            + "결과 기록 실패 500(PAYMENT_013 — 상태 조회). 헤더 없음 400(PAYMENT_113) · UUID 아님 400(PAYMENT_114)."
    )
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/confirm")
    public ResponseEntity<Response<PaymentResponse>> confirm(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "멱등 키 (UUID). 같은 승인 요청의 재전송에는 같은 값을 보낸다", example = "3f2b8c1e-6a4d-4e2f-9b1a-7c5d2e8f0a11")
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) UUID idempotencyKey,
        @RequestBody @Valid PaymentConfirmRequest request
    ) {
        PaymentResponse response = paymentWebUseCase.confirm(member.memberId(), idempotencyKey, request.toCommand());
        return ResponseEntity.ok(Response.success(response));
    }

    @Operation(
        summary = "결제 상태 조회",
        description = "내 결제의 현재 상태다 — 결제 확인 중(UNKNOWN) · 승인 처리 중 응답 뒤 몇 초 간격으로 다시 부른다. 없거나 다른 회원의 결제는 404(PAYMENT_001)."
    )
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/{paymentId}")
    public ResponseEntity<Response<PaymentResponse>> getPayment(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "결제 아이디", example = "4611686018427387906") @PathVariable long paymentId
    ) {
        return ResponseEntity.ok(Response.success(paymentWebUseCase.getPayment(member.memberId(), paymentId)));
    }
}
