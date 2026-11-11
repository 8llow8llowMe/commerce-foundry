package com.commercefoundry.domainlayer.order.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.request.ClaimDecisionRequest;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.request.ClaimRetryRequest;
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

@Tag(name = "[어드민] 클레임", description = "반품 · 결제 후 취소 클레임 목록 · 상세 조회와 결제 후 취소 다시 시도 · 수동 환불 확정 · 복원 생략 종결")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/claims")
public class ClaimAdminController {
    private final ClaimAdminUseCase useCase;

    @Operation(summary = "클레임 목록 조회", description = "클레임 아이디 내림차순. 유형은 반품(RETURN) · 결제 후 취소(CANCELLATION), 상태는 REQUESTED · REFUNDED · COMPLETED · "
        + "REFUND_FAILED · RESTORATION_FAILED 다(반품은 아직 REQUESTED 만 생긴다). 예상 환불액 null은 미산정이다. "
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

    @Operation(summary = "결제 후 취소 다시 시도", description = "결제 취소 · 재고 복원이 멈춘 취소 접수를 같은 금액 · 같은 접수로 다시 보낸 뒤 현재 상세를 준다. "
        + "본문 expectedVersion 은 상세의 recovery.version 이다. 200 이어도 결제사가 다시 거절했을 수 있으니 status · recovery.situation 을 다시 읽는다. "
        + "버전 불일치 · 동시 변경 409(ORDER_037), 다시 시도할 수 없는 접수 409(ORDER_038), 자동 처리 중 409(ORDER_039), 자동 처리 꺼짐 503(ORDER_040), "
        + "결제 취소 결과 미반영 503(ORDER_036), 없는 접수 404(ORDER_032), expectedVersion 누락 · 음수 400(ORDER_169).")
    @PreAuthorize(AdminAuthority.ORDER_MANAGE)
    @AdminAudited(action = "CLAIM_RETRY")
    @PostMapping("/{claimId}/retries")
    public ResponseEntity<Response<ClaimAdminResponse>> retry(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "클레임 아이디") @PathVariable @Positive(message = OrderValidationMessage.CLAIM_ID_POSITIVE) long claimId,
        @RequestBody @Valid ClaimRetryRequest request
    ) {
        return ResponseEntity.ok(Response.success(useCase.retry(claimId, request.expectedVersion(), admin.memberId())));
    }

    @Operation(summary = "결제 후 취소 환불 완료로 확정", description = "결제사 · 결제 관리 화면에서 환불을 확인한 '환불 실패' 접수를 환불 완료로 기록하고 재고 복원을 잇는다. "
        + "결제사에 다시 요청하지 않는다 — 실제 환불을 확인한 뒤에만 누른다. 대표 관리자만(SETTLEMENT_MANAGE). 본문 {expectedVersion, reason}. "
        + "결제사 거절은 늘, 결과 미확인은 다시 시도 기한(접수 + 14일)이 지나 다시 시도가 닫힌 뒤에만 확정한다. "
        + "버전 불일치 · 동시 변경 409(ORDER_037), 환불 실패가 아님 · 기한 안의 결과 미확인(다시 시도) · 주문이 취소 요청 · 취소 완료가 아님 409(ORDER_041), "
        + "자동 처리 꺼짐 503(ORDER_040), 없는 접수 404(ORDER_032), 사유 없음 · 공백만 400(ORDER_170) · 200자 초과(ORDER_148) · 제어 · 서식 문자(ORDER_151), "
        + "expectedVersion 400(ORDER_169), 양수가 아닌 아이디 400(ORDER_166).")
    @PreAuthorize(AdminAuthority.SETTLEMENT_MANAGE)
    @AdminAudited(action = "CLAIM_REFUND_CONFIRM")
    @PostMapping("/{claimId}/refund-confirmation")
    public ResponseEntity<Response<ClaimAdminResponse>> confirmRefund(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "클레임 아이디") @PathVariable @Positive(message = OrderValidationMessage.CLAIM_ID_POSITIVE) long claimId,
        @RequestBody @Valid ClaimDecisionRequest request
    ) {
        return ResponseEntity.ok(Response.success(useCase.confirmRefund(claimId, request.expectedVersion(), request.reason(), admin.memberId())));
    }

    @Operation(summary = "결제 후 취소 재고 복원 없이 종결", description = "재고 복원이 거절된 취소 접수를 복원 없이 완료로 닫는다. 재고는 재고 조정 화면에서 먼저 맞춘다. "
        + "복원 결과 미확인 접수는 실제로 복원됐을 수 있어 종결할 수 없다(다시 시도). 주문 상태가 맞지 않아 닫힌 접수도 종결 대상이 아니다. "
        + "대표 관리자만(SETTLEMENT_MANAGE). 본문 {expectedVersion, reason}. "
        + "버전 불일치 409(ORDER_037), 종결할 수 없는 접수 · 주문이 취소 완료가 아님 409(ORDER_041), 없는 접수 404(ORDER_032), 본문 검증 400(ORDER_169 · 170 · 148 · 151), 양수가 아닌 아이디 400(ORDER_166).")
    @PreAuthorize(AdminAuthority.SETTLEMENT_MANAGE)
    @AdminAudited(action = "CLAIM_COMPLETE")
    @PostMapping("/{claimId}/completion")
    public ResponseEntity<Response<ClaimAdminResponse>> complete(
        @AuthenticationPrincipal MemberPrincipal admin,
        @Parameter(description = "클레임 아이디") @PathVariable @Positive(message = OrderValidationMessage.CLAIM_ID_POSITIVE) long claimId,
        @RequestBody @Valid ClaimDecisionRequest request
    ) {
        return ResponseEntity.ok(Response.success(useCase.complete(claimId, request.expectedVersion(), request.reason(), admin.memberId())));
    }
}
