package com.commercefoundry.domainlayer.order.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.request.ClaimDecisionRequest;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.ClaimAdminResponse;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.application.model.ClaimAdminFilter;
import com.commercefoundry.domainlayer.order.application.port.in.ClaimAdminUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.principal.AdminAuthority;
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

@Tag(name = "[어드민] 클레임", description = "반품 · 결제 후 취소 클레임 목록 · 상세 조회와 결제 후 취소 수습(재시도 · 거절 · 수동 환불 확정 · 복원 생략 종결)")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/claims")
public class ClaimAdminController {
    private final ClaimAdminUseCase useCase;

    @Operation(summary = "클레임 목록 조회", description = "클레임 아이디 내림차순. 유형은 반품(RETURN) · 결제 후 취소(CANCELLATION), 상태는 REQUESTED · REFUNDED · COMPLETED · "
        + "REFUND_FAILED · RESTORATION_FAILED · APPROVED · REJECTED 다(반품은 아직 REQUESTED 만 생기고, APPROVED 는 반품 승인용으로 아직 생기지 않는다). 예상 환불액 null은 미산정이다. "
        + "page는 0 이상, size는 1~100이며 page×size가 2,147,483,647을 넘으면 조회 전에 400(ORDER_167)이다.")
    @PreAuthorize(AdminAuthority.ORDER_READ)
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
    @PreAuthorize(AdminAuthority.ORDER_READ)
    @GetMapping("/{claimId}")
    public ResponseEntity<Response<ClaimAdminResponse>> getClaim(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "클레임 아이디") @PathVariable @Positive(message = OrderValidationMessage.CLAIM_ID_POSITIVE) long claimId
    ) {
        return ResponseEntity.ok(Response.success(useCase.getClaim(claimId, admin.memberId())));
    }

    @Operation(summary = "결제 후 취소 클레임 재시도",
        description = "환불 · 재고 복원을 지금 다시 돌리고 처리 뒤 상세를 돌려준다. 환불 실패면 환불 대기로, 재고 복원 실패면 복원 대기로 다시 열고(횟수 0), "
            + "대기 중이면 선점한 뒤 다시 보낸다. 같은 클레임 아이디로 보내 PG · 재고는 한 번만 바뀐다. 이미 완료면 바꾸지 않고 200 이다. "
            + "원격 결과를 아직 모르면 200 이고 상태는 대기로 남아 재시도 스케줄러가 잇는다. 거절된 클레임 · 반품 · 주문 상태 이상으로 닫힌 클레임 409(ORDER_036), "
            + "스케줄러 · 고객 요청과 겹침 409(ORDER_025), 결제 후 취소가 닫혀 있음(재시도 스케줄러 꺼짐) 503(ORDER_035), 없는 클레임 404(ORDER_032).")
    @PreAuthorize(AdminAuthority.ORDER_MANAGE)
    @AdminAudited(action = "CLAIM_RETRY")
    @PostMapping("/{claimId}/retries")
    public ResponseEntity<Response<ClaimAdminResponse>> retry(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "클레임 아이디") @PathVariable @Positive(message = OrderValidationMessage.CLAIM_ID_POSITIVE) long claimId
    ) {
        return ResponseEntity.ok(Response.success(useCase.retry(claimId, admin.memberId())));
    }

    @Operation(summary = "결제 후 취소 거절",
        description = "PG 가 환불을 거절해 환불 실패로 닫힌 취소를 거절하고, 주문을 취소 요청 전 상태(결제 완료 · 제작 중 · 상품 준비중)로 되돌린다. "
            + "사유는 주문 이력(관리자)에 남는다. 거절 뒤 고객이 같은 주문을 다시 취소하면 409(ORDER_037)다. 이미 거절이면 바꾸지 않고 200 이다. "
            + "환불 결과를 확인하지 못한 클레임(REFUND_UNCONFIRMED) 409(ORDER_038 — 재시도하거나 수동 환불 확정), 환불 실패가 아닌 상태 · 반품 409(ORDER_036), "
            + "주문이 취소 요청이 아님 409(ORDER_020), 되돌릴 상태를 이력에서 못 찾음 500(ORDER_041), 겹친 변경 409(ORDER_025), 사유 없음 · 200자 초과 · 제어 문자 "
            + "400(ORDER_169 · ORDER_148 · ORDER_151), 없는 클레임 404(ORDER_032).")
    @PreAuthorize(AdminAuthority.ORDER_MANAGE)
    @AdminAudited(action = "CLAIM_REJECT")
    @PostMapping("/{claimId}/rejection")
    public ResponseEntity<Response<ClaimAdminResponse>> reject(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "클레임 아이디") @PathVariable @Positive(message = OrderValidationMessage.CLAIM_ID_POSITIVE) long claimId,
        @RequestBody @Valid ClaimDecisionRequest request
    ) {
        return ResponseEntity.ok(Response.success(useCase.reject(claimId, request.reason(), admin.memberId())));
    }

    @Operation(summary = "결제 후 취소 수동 환불 확정",
        description = "운영자가 PG 관리자 화면 · 결제 기록에서 환불을 확인한 환불 실패 클레임(실패 코드 무관)을 환불 완료로 확정하고, 주문을 취소 완료로 바꾼 뒤 "
            + "재고 복원을 이어서 돌린다. 사유는 주문 이력(관리자)에 남는다. 복원 결과를 아직 모르면 200 이고 재시도 스케줄러가 잇는다. "
            + "이미 환불 확정 뒤(환불 완료 · 완료 · 재고 복원 실패)면 바꾸지 않고 200 이다. 그 밖의 상태 · 반품 409(ORDER_036), 주문이 취소 요청 · 취소 완료가 아님 "
            + "409(ORDER_020), 겹친 변경 409(ORDER_025), 사유 검증 400(ORDER_169 · ORDER_148 · ORDER_151), 없는 클레임 404(ORDER_032).")
    @PreAuthorize(AdminAuthority.ORDER_MANAGE)
    @AdminAudited(action = "CLAIM_REFUND_CONFIRM")
    @PostMapping("/{claimId}/refund-confirmation")
    public ResponseEntity<Response<ClaimAdminResponse>> confirmRefund(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "클레임 아이디") @PathVariable @Positive(message = OrderValidationMessage.CLAIM_ID_POSITIVE) long claimId,
        @RequestBody @Valid ClaimDecisionRequest request
    ) {
        return ResponseEntity.ok(Response.success(useCase.confirmRefund(claimId, request.reason(), admin.memberId())));
    }

    @Operation(summary = "결제 후 취소 복원 생략 종결",
        description = "재고 복원이 종결 실패한 클레임을 복원 없이 완료로 닫는다. 재고는 먼저 재고 조정으로 맞춘다. 주문은 바꾸지 않는다. 이미 완료면 바꾸지 않고 200 이다. "
            + "재고 복원 실패가 아닌 상태 · 반품 409(ORDER_036), 겹친 변경 409(ORDER_025), 사유 검증 400(ORDER_169 · ORDER_148 · ORDER_151), 없는 클레임 404(ORDER_032).")
    @PreAuthorize(AdminAuthority.ORDER_MANAGE)
    @AdminAudited(action = "CLAIM_COMPLETE")
    @PostMapping("/{claimId}/completion")
    public ResponseEntity<Response<ClaimAdminResponse>> complete(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "클레임 아이디") @PathVariable @Positive(message = OrderValidationMessage.CLAIM_ID_POSITIVE) long claimId,
        @RequestBody @Valid ClaimDecisionRequest request
    ) {
        return ResponseEntity.ok(Response.success(useCase.complete(claimId, request.reason(), admin.memberId())));
    }
}
