package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.order.application.command.ClaimReturnApprovalCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 어드민 반품 승인 (#17 B2c-2) — 운영자가 본 상세 화면의 버전 · 검수 결과(귀책 · 재고 되돌림 · 차감액)와 화면에서 본 환불액. 환불액은 서버가 다시 계산하고 대조에만 쓴다.
 */
@Schema(description = "반품 승인")
public record ClaimReturnApprovalRequest(
    @Schema(description = "상세 조회 응답의 recovery.version", example = "0")
    @NotNull(message = OrderValidationMessage.CLAIM_EXPECTED_VERSION)
    @PositiveOrZero(message = OrderValidationMessage.CLAIM_EXPECTED_VERSION)
    Long expectedVersion,

    @Schema(description = "귀책(CUSTOMER · SELLER). 없으면 상세의 returnDecision.defaultFault(항목 사유로 정한 기본 귀책)다", nullable = true, example = "CUSTOMER")
    ReturnRefundPolicy.Fault fault,

    @Schema(description = "검수 뒤 재고로 되돌릴지 — false 면(불량 폐기 등) 환불 뒤 재고 복원을 부르지 않는다", example = "true")
    @NotNull(message = OrderValidationMessage.RETURN_RESTOCK_REQUIRED)
    Boolean restock,

    @Schema(description = "정책 금액에서 뺄 금액(반품 배송비 · 훼손 등). 없으면 amount 0 으로 보낸다")
    @NotNull(message = OrderValidationMessage.RETURN_DEDUCTION_AMOUNT)
    @Valid
    DeductionAmount deductionAmount,

    @Schema(description = "화면에서 본 환불액 = 고른 귀책의 정책 금액 − 차감액. 서버 계산과 다르면 409(ORDER_046)다")
    @NotNull(message = OrderValidationMessage.RETURN_EXPECTED_REFUND_AMOUNT)
    @Valid
    ExpectedRefundAmount expectedRefundAmount
) {

    public ClaimReturnApprovalCommand toCommand(long claimId) {
        return new ClaimReturnApprovalCommand(claimId, expectedVersion, fault, restock, Money.of(deductionAmount.amount(), deductionAmount.currency()),
            Money.of(expectedRefundAmount.amount(), expectedRefundAmount.currency()));
    }

    @Schema(description = "차감액 — 최소 화폐 단위 정수와 통화")
    public record DeductionAmount(
        @Schema(description = "최소 화폐 단위 금액 (KRW 는 원, USD 는 센트), 0 이상", example = "3000")
        @NotNull(message = OrderValidationMessage.RETURN_DEDUCTION_AMOUNT)
        @PositiveOrZero(message = OrderValidationMessage.RETURN_DEDUCTION_AMOUNT)
        Long amount,

        @Schema(description = "통화 (ISO 4217) — 주문 통화와 같아야 한다", example = "KRW")
        @NotNull(message = OrderValidationMessage.RETURN_DEDUCTION_AMOUNT)
        CurrencyCode currency
    ) {
    }

    @Schema(description = "예상 환불액 — 최소 화폐 단위 정수와 통화")
    public record ExpectedRefundAmount(
        @Schema(description = "최소 화폐 단위 금액 (KRW 는 원, USD 는 센트), 양수", example = "24000")
        @NotNull(message = OrderValidationMessage.RETURN_EXPECTED_REFUND_AMOUNT)
        @Positive(message = OrderValidationMessage.RETURN_EXPECTED_REFUND_AMOUNT)
        Long amount,

        @Schema(description = "통화 (ISO 4217) — 주문 통화와 같아야 한다", example = "KRW")
        @NotNull(message = OrderValidationMessage.RETURN_EXPECTED_REFUND_AMOUNT)
        CurrencyCode currency
    ) {
    }
}
