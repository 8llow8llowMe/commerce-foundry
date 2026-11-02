package com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 서비스 간 결제 조회 응답 (#308) — order-service 가 {@code payment-approved} 이벤트의 진위를 이 값으로 확정한다.
 * PG 결제키 · 고객 멱등 키 · 주문명 · PG 원문 상태 · 간편결제 제공자 · 실패 사유 · 미확정 이유 · 주문 반영 기록은 내리지 않는다 — 진위 확인에 필요한 칸만 둔다.
 * 고객 응답({@code PaymentResponse})과 공유하지 않는다.
 */
@Builder
@Schema(description = "서비스 간 결제 조회 응답")
public record PaymentInternalResponse(

    @Schema(description = "결제 아이디", example = "4611686018427387906")
    String paymentId,

    @Schema(description = "주문 아이디", example = "4611686018427387905")
    String orderId,

    @Schema(description = "결제한 회원 아이디", example = "4611686018427387904")
    String memberId,

    @Schema(description = "결제 상태 — code 가 PaymentStatus 제어 코드다(DONE 이면 결제 완료)",
        example = "{\"code\":\"DONE\",\"name\":\"결제 완료\",\"description\":\"결제가 승인되었습니다.\"}")
    CodeNameDescriptionMetadata status,

    @Schema(description = "결제 금액 — 최소 화폐 단위 정수와 통화")
    MoneyItem amount,

    @Schema(description = "결제수단. 승인 전이면 null", nullable = true, example = "{\"code\":\"EASY_PAY\",\"name\":\"간편결제\",\"description\":\"간편결제\"}")
    CodeNameDescriptionMetadata method,

    @Schema(description = "승인 시각 (UTC). 승인 전이면 null", nullable = true, example = "2026-10-21T01:00:03Z")
    Instant approvedAt
) {
}
