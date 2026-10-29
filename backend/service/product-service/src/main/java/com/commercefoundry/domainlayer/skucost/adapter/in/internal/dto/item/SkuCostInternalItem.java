package com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 한 시각에 적용되는 원가 한 유형. 원가는 고객에게 보이면 안 되는 값이다 — 이 항목은 허용 호출자(operations-service)에게만 나간다.
 */
@Builder
@Schema(description = "시점에 적용되는 원가 한 유형")
public record SkuCostInternalItem(

    @Schema(
        description = "원가 유형 — GOODS(매입 · 제작 원가) · PACKAGING(포장 변동비)",
        example = "{\"code\": \"GOODS\", \"name\": \"매입 · 제작 원가\", \"description\": \"상품 한 개를 매입하거나 제작하는 데 든 원가입니다.\"}"
    )
    CodeNameDescriptionMetadata costType,

    @Schema(description = "원가 — 최소 화폐 단위 정수와 통화. 0 은 0원이라고 명시한 값이다")
    MoneyItem amount,

    @Schema(description = "이 원가의 적용 시작 시각 (포함, UTC ISO-8601)", example = "2026-10-01T00:00:00Z")
    Instant effectiveFrom,

    @Schema(description = "이 원가를 기록한 시각 (UTC, ISO-8601)", example = "2026-09-30T09:00:00Z")
    Instant recordedAt
) {
}
