package com.commercefoundry.domainlayer.payment.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 결제사가 거절한 이유 — 결제 실패(FAILED)일 때만 내린다.
 */
@Schema(description = "결제 실패 사유 (결제 실패일 때만)")
public record PaymentFailureItem(

    @Schema(description = "결제사 오류 코드", example = "REJECT_CARD_COMPANY")
    String code,

    @Schema(description = "결제사 안내 문구. 없을 수 있다", example = "카드사에서 거절했습니다. 카드사에 문의해 주세요.", nullable = true)
    String message
) {
}
