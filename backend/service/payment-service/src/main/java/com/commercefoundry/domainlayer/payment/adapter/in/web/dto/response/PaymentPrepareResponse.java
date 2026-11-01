package com.commercefoundry.domainlayer.payment.adapter.in.web.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.item.PaymentFailureItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 결제 준비 응답 — {@link PaymentResponse} 의 칸에 주문의 결제 기한을 더한다. FE 는 {@code pgOrderId} · {@code amount} · {@code orderName} 으로 결제위젯을 띄운다.
 */
@Builder
@Schema(description = "결제 준비 응답")
public record PaymentPrepareResponse(

    @Schema(description = "결제 아이디", example = "4611686018427387906")
    String paymentId,

    @Schema(description = "주문 아이디", example = "4611686018427387905")
    String orderId,

    @Schema(description = "결제창에 넘기는 주문번호 (토스 orderId)", example = "4611686018427387906")
    String pgOrderId,

    @Schema(description = "결제 상태 — 새 결제면 READY. 이미 승인 중 · 확인 중 · 완료된 결제가 있으면 그 상태",
        example = "{\"code\":\"READY\",\"name\":\"결제 대기\",\"description\":\"결제를 준비했고 고객의 결제 인증을 기다리고 있습니다.\"}")
    CodeNameDescriptionMetadata status,

    @Schema(description = "결제 금액 — 결제위젯에 이 금액을 넘긴다")
    MoneyItem amount,

    @Schema(description = "결제창 주문명", example = "생활한복 저고리 외 2건")
    String orderName,

    @Schema(description = "결제수단. 승인 전이면 null", nullable = true)
    CodeNameDescriptionMetadata method,

    @Schema(description = "간편결제 제공자. 간편결제가 아니거나 승인 전이면 null", nullable = true)
    String easyPayProvider,

    @Schema(description = "승인 시각 (UTC). 승인 전이면 null", nullable = true)
    Instant approvedAt,

    @Schema(description = "결제 실패 사유. 결제 실패(FAILED)일 때만 있다", nullable = true)
    PaymentFailureItem failure,

    @Schema(description = "주문의 결제 기한 (UTC) — 지나면 주문이 정리된다", example = "2026-10-21T01:30:00Z")
    Instant paymentDueAt
) {
}
