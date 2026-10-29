package com.commercefoundry.domainlayer.skucost.adapter.in.internal.dto.request;

import com.commercefoundry.domainlayer.skucost.application.command.SkuCostAtQueryCommand;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostValidationMessage;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * 서비스 간 시점 원가 조회 요청 (operations-service → product-service). 같은 (SKU, 시각) 쌍은 하나로 합친다 — 상한 100 은 합치기 전 원문 개수다.
 */
@Builder
@Schema(description = "시점 원가 조회 요청 (operations-service → product-service)")
public record SkuCostAtQueryRequest(

    @Schema(description = "원가를 고를 (SKU, 시각) 쌍 (1~100개)")
    @NotEmpty(message = SkuCostValidationMessage.QUERY_ITEMS_REQUIRED)
    @Size(max = SkuCostAtQueryRequest.MAX_ITEMS, message = SkuCostValidationMessage.QUERY_ITEMS_SIZE_MAX)
    List<@Valid @NotNull(message = SkuCostValidationMessage.QUERY_ITEM_REQUIRED) Item> items
) {

    /** 한 번에 받는 항목 상한 — 서비스 간 벌크 조회 상한과 같다 ({@code api-design-guide.md} §1-3). */
    public static final int MAX_ITEMS = 100;

    public SkuCostAtQueryCommand toCommand() {
        return new SkuCostAtQueryCommand(items.stream().map(item -> new SkuCostAtQueryCommand.Point(item.skuId(), item.at())).toList());
    }

    /**
     * 쌍 하나. 교차 검증({@code @AssertTrue})의 {@code field} 는 메서드 이름에서 온다({@code items[i].atInRange}).
     */
    @Builder
    @Schema(description = "원가를 고를 SKU 와 시각")
    public record Item(

        @Schema(description = "SKU 아이디", example = "4611686018427387999")
        @NotNull(message = SkuCostValidationMessage.QUERY_SKU_ID_REQUIRED)
        @Positive(message = SkuCostValidationMessage.QUERY_SKU_ID_POSITIVE)
        Long skuId,

        @Schema(
            description = "원가를 고를 시각 (UTC ISO-8601, 보통 주문 시각). 마이크로초로 잘라 비교한다. 미래여도 받지만 그 결과는 이후 원가 추가로 바뀔 수 있다. 원가 미입력 유형은 지난 시각이어도 이후 첫 입력으로 채워질 수 있다",
            example = "2026-10-29T01:00:00Z"
        )
        @NotNull(message = SkuCostValidationMessage.QUERY_AT_REQUIRED)
        Instant at
    ) {

        /** 받는 시각 범위 (양 끝 포함). 밖의 값은 입력 실수이고, 상한이 없으면 DB 가 받지 못하는 시각이 질의 바인딩에서 500 으로 샌다. */
        static final Instant AT_MIN = Instant.EPOCH;
        static final Instant AT_MAX = Instant.parse("9999-12-31T23:59:59.999999Z");

        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(message = SkuCostValidationMessage.QUERY_AT_RANGE)
        public boolean isAtInRange() {
            return at == null || (!at.isBefore(AT_MIN) && !at.isAfter(AT_MAX));
        }
    }
}
