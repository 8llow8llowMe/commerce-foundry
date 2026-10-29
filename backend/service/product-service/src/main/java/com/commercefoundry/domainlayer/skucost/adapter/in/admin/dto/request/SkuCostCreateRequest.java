package com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.validation.ValidSkuCostReason;
import com.commercefoundry.domainlayer.skucost.application.command.SkuCostCreateCommand;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostValidationMessage;
import com.commercefoundry.domainlayer.skucost.application.model.SkuCostTypeCode;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import lombok.Builder;

/**
 * SKU 원가 이력 한 행 추가 요청. 원가가 바뀌면 고치지 않고 새 적용 시작 시각으로 행을 더한다.
 * 단조 · 통화 고정 · 미래 한도는 Processor 가 도메인 오류 코드(074 · 075 · 076)로 본다. 기록한 어드민은 토큰의 memberId 다 — 본문으로 받지 않는다.
 *
 * <p>교차 검증은 {@code @AssertTrue} 메서드다. 오류의 {@code field} 는 메서드 이름에서 온다({@code effectiveFromInRange}).
 */
@Builder
@Schema(description = "SKU 원가 이력 추가 요청")
public record SkuCostCreateRequest(

    @Schema(description = "원가 유형 — GOODS(매입 · 제작 원가) · PACKAGING(포장 변동비)", example = "GOODS", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = SkuCostValidationMessage.COST_TYPE_REQUIRED)
    SkuCostTypeCode costType,

    @Schema(description = "원가. 같은 SKU · 유형의 기존 이력과 통화가 같아야 한다 (다르면 409 PRODUCT_076)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = SkuCostValidationMessage.AMOUNT_REQUIRED)
    @Valid
    MoneyRequest amount,

    @Schema(
        description = "적용 시작 시각 (포함, UTC ISO-8601). 없으면 지금이다. 같은 유형의 최신 이력 시작보다 뒤여야 하고(아니면 409 PRODUCT_075), "
            + "지금부터 5분 뒤까지만 받는다(넘으면 400 PRODUCT_074). 최신보다 뒤라면 과거로 소급해도 된다. 마이크로초로 잘라 저장한다",
        example = "2026-10-01T00:00:00Z", nullable = true
    )
    Instant effectiveFrom,

    @Schema(
        description = "변경 사유. 앞뒤 공백(전각 공백 포함)을 빼고 저장하며 뺀 뒤 1~200자다. 공백 · 서식 문자만이면 PRODUCT_205, "
            + "제어 · 서식 문자(개행 · 탭 · 제로폭 공백 등)가 있으면 PRODUCT_207 이다",
        example = "10월 원단 단가 인상", requiredMode = Schema.RequiredMode.REQUIRED
    )
    @ValidSkuCostReason
    String reason
) {

    /** 받을 수 있는 가장 이른 적용 시작 시각 (포함). 이보다 이른 값은 입력 실수로 본다. */
    static final Instant EFFECTIVE_FROM_MIN = Instant.EPOCH;

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = SkuCostValidationMessage.EFFECTIVE_FROM_MIN)
    public boolean isEffectiveFromInRange() {
        return effectiveFrom == null || !effectiveFrom.isBefore(EFFECTIVE_FROM_MIN);
    }

    /**
     * @param skuId         경로의 SKU 아이디
     * @param adminMemberId access 토큰의 memberId
     */
    public SkuCostCreateCommand toCommand(long skuId, long adminMemberId) {
        return SkuCostCreateCommand.builder()
            .skuId(skuId)
            .costType(costType.toDomain())
            .amount(amount.toMoney())
            .effectiveFrom(effectiveFrom)
            .reason(reason.strip())
            .adminMemberId(adminMemberId)
            .build();
    }

    @Schema(description = "금액 — 최소 화폐 단위 정수와 통화")
    public record MoneyRequest(

        @Schema(description = "최소 화폐 단위 금액 (KRW 는 원, USD 는 센트). 0 이상 — 0 은 0원이라고 명시한 값이다", example = "12000",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = SkuCostValidationMessage.AMOUNT_VALUE_REQUIRED)
        @PositiveOrZero(message = SkuCostValidationMessage.AMOUNT_VALUE_NON_NEGATIVE)
        Long amount,

        @Schema(description = "통화 코드 (KRW · USD). 판매가 통화와 같을 필요는 없다", example = "KRW", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = SkuCostValidationMessage.AMOUNT_CURRENCY_REQUIRED)
        CurrencyCode currency
    ) {

        Money toMoney() {
            return Money.of(amount, currency);
        }
    }
}
