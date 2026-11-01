package com.commercefoundry.domainlayer.payment.adapter.in.web.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.payment.adapter.in.web.dto.item.PaymentFailureItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 고객 결제 응답 — 결제 승인 · 상태 조회 공통. 결제키 · 미확정 이유 · 주문 반영 여부 · 승인 시작 시각은 내리지 않는다(내부 처리용).
 */
@Builder
@Schema(description = "결제 응답")
public record PaymentResponse(

    @Schema(description = "결제 아이디", example = "4611686018427387906")
    String paymentId,

    @Schema(description = "주문 아이디", example = "4611686018427387905")
    String orderId,

    @Schema(description = "결제창에 넘기는 주문번호 (토스 orderId)", example = "4611686018427387906")
    String pgOrderId,

    @Schema(description = "결제 상태 — READY(결제 대기) · IN_PROGRESS(승인 중) · DONE(결제 완료) · FAILED(결제 실패) · UNKNOWN(결제 확인 중) · ABORTED(결제 중단) 등",
        example = "{\"code\":\"DONE\",\"name\":\"결제 완료\",\"description\":\"결제가 승인되었습니다.\"}")
    CodeNameDescriptionMetadata status,

    @Schema(description = "결제 금액")
    MoneyItem amount,

    @Schema(description = "결제창 주문명", example = "생활한복 저고리 외 2건")
    String orderName,

    @Schema(description = "결제수단. 승인 전이면 null", nullable = true, example = "{\"code\":\"EASY_PAY\",\"name\":\"간편결제\",\"description\":\"간편결제\"}")
    CodeNameDescriptionMetadata method,

    @Schema(description = "간편결제 제공자. 간편결제가 아니거나 승인 전이면 null", nullable = true, example = "카카오페이")
    String easyPayProvider,

    @Schema(description = "승인 시각 (UTC). 승인 전이면 null", nullable = true, example = "2026-10-21T01:00:03Z")
    Instant approvedAt,

    @Schema(description = "결제 실패 사유. 결제 실패(FAILED)일 때만 있다", nullable = true)
    PaymentFailureItem failure
) {
}
