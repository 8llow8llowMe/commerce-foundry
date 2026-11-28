package com.commercefoundry.domainlayer.settlement.adapter.in.internal.dto.response;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "PG 매출일 하루의 정산 수집 · 대사 결과. 결제키 · 주문/회원/결제 식별자는 싣지 않는다")
public record SettlementReconcileResponse(

    @Schema(description = "PG 매출일 (KST)", example = "2026-11-20")
    LocalDate soldDate,

    @Schema(description = "수집한 PG 정산 거래 수 (KRW 거래만)")
    long itemCount,

    @Schema(description = "PG 거래 금액 합계 — 승인 +, 취소 − 라 음수일 수 있다. 최소 화폐 단위 정수와 통화")
    MoneyItem pgAmountTotal,

    @Schema(description = "이번 대사가 본 결제들의 미해결(OPEN) 불일치 수")
    long mismatchCount,

    @Schema(description = "이번 대사에서 새로 열리거나 다시 열린 불일치 수")
    long newMismatchCount,

    @Schema(description = "이번 대사에서 더는 어긋나지 않아 자동으로 닫은 불일치 수")
    long autoClearedCount,

    @Schema(description = "PG 누락으로 보이지만 유예(매출일 + 3일) 중이라 기록하지 않은 결제 수")
    long graceDeferredCount,

    @Schema(description = "매출일 정산 상태 코드 · 이름 · 설명 (대사까지 끝나면 RECONCILED)")
    CodeNameDescriptionMetadata status
) {
}
