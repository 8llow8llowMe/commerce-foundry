package com.commercefoundry.domainlayer.payment.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "우리 PG 취소 호출 밖에서 확인해 기록한 환불의 채널별 합계. 두 금액은 결제와 같은 통화이며 없는 채널은 0 이다")
public record PaymentAdminExternalRefundItem(
    @Schema(description = "PG 상점관리자 등 PG 에서 환불한 합계 — PG 잔액이 줄었다")
    MoneyItem pgAmount,

    @Schema(description = "계좌 송금 등 PG 밖으로 환불한 합계 — PG 잔액 · 결제 상태에는 반영하지 않는다")
    MoneyItem offPgAmount
) {
}
