package com.commercefoundry.domainlayer.settlement.adapter.in.internal.dto.request;

import com.commercefoundry.domainlayer.settlement.application.exception.SettlementValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

@Schema(description = "정산 수집 · 대사 요청 — PG 매출일 하루")
public record SettlementReconcileRequest(

    @Schema(description = "대사할 PG 매출일 (KST, yyyy-MM-dd). 오늘부터 90일 전 사이", example = "2026-11-20")
    @NotNull(message = SettlementValidationMessage.SOLD_DATE_REQUIRED)
    LocalDate soldDate
) {
}
