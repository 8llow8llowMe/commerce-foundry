package com.commercefoundry.domainlayer.payment.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "어드민 결제 목록 항목")
public record PaymentAdminItem(
    @Schema(description = "결제 아이디", example = "4611686018427387904")
    String paymentId,

    @Schema(description = "주문 아이디", example = "4611686018427387905")
    String orderId,

    @Schema(description = "결제 시도의 저장된 상태")
    CodeNameDescriptionMetadata status,

    @Schema(description = "저장된 결제 시도 원금. 환불 차감 잔액이나 PG 대사 결과가 아니다")
    MoneyItem amount,

    @Schema(description = "결제 수단. 승인 전이면 null", nullable = true)
    CodeNameDescriptionMetadata method,

    @Schema(description = "미확정 사유. 해결 후에도 과거 원인으로 남을 수 있다", nullable = true)
    CodeNameDescriptionMetadata unknownReason,

    @Schema(description = "주문의 성공 회신을 수신했는지 여부. false 자체가 재시도 지시가 아니다")
    boolean orderSynced,

    @Schema(description = "주문 거절 회신 코드. 미확인 또는 성공이면 null", nullable = true)
    String orderSyncFailureCode,

    @Schema(description = "결제 시도 생성 시각(UTC)")
    Instant createdAt,

    @Schema(description = "결제 시도 수정 시각(UTC)")
    Instant updatedAt,

    @Schema(description = "승인 시작 시각(UTC)", nullable = true)
    Instant approvalStartedAt,

    @Schema(description = "승인 완료 시각(UTC)", nullable = true)
    Instant approvedAt,

    @Schema(description = "외부 확인 환불 합계. 그런 취소 원장이 없으면 null. amount 원금에서 빼지 않은 값이다", nullable = true)
    PaymentAdminExternalRefundItem externalRefund
) {
}
