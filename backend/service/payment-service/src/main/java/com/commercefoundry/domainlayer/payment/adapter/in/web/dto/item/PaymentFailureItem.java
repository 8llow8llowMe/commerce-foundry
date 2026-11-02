package com.commercefoundry.domainlayer.payment.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 고객에게 안내하는 결제 실패 사유 — 결제 실패(FAILED)일 때만 내린다.
 */
@Schema(description = "결제 실패 사유 (결제 실패일 때만)")
public record PaymentFailureItem(

    @Schema(description = "결제사 오류 코드", example = "REJECT_CARD_COMPANY")
    String code,

    @Schema(description = "서비스가 정한 고객 안내 문구. 결제사 원문은 노출하지 않는다", example = "결제를 완료하지 못했습니다. 다른 결제 수단으로 다시 시도해 주세요.")
    String message
) {
}
