package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.product.application.command.SkuBulkUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminValidationMessage;
import com.commercefoundry.domainlayer.product.application.model.ProductSaleStatusFilter;
import com.commercefoundry.domainlayer.product.domain.model.HsCode;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@Schema(description = "기존 SKU 편집 속성 전체 교체. null/생략한 무게·HS 코드는 삭제한다")
public record SkuBulkUpdateRequest(
    @Schema(description = "편집용 상세에서 읽은 상품 버전", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = SkuAdminValidationMessage.VERSION)
    @PositiveOrZero(message = SkuAdminValidationMessage.VERSION)
    @JsonDeserialize(using = IntegerLongDeserializer.class)
    Long version,

    @Schema(description = "기존 SKU 전체. 아이디 중복·누락·추가 불가", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = SkuAdminValidationMessage.SKUS)
    @Size(min = 1, max = 100, message = SkuAdminValidationMessage.SKUS)
    List<@NotNull(message = SkuAdminValidationMessage.SKUS) @Valid Item> skus
) {
    public SkuBulkUpdateCommand toCommand(long productId, long actorMemberId) {
        return new SkuBulkUpdateCommand(productId, version, actorMemberId, skus.stream().map(Item::toCommand).toList());
    }

    @Schema(description = "SKU 편집 속성. 아이디는 숫자 또는 십진수 문자열")
    public record Item(
        @Schema(description = "기존 SKU 아이디", example = "4611686018427387904", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = SkuAdminValidationMessage.ID)
        @Positive(message = SkuAdminValidationMessage.ID)
        @JsonDeserialize(using = IntegerLongDeserializer.class)
        Long skuId,

        @Schema(description = "추가금. 상품 통화와 같아야 한다", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = SkuAdminValidationMessage.PRICE_REQUIRED)
        @Valid
        Price additionalPrice,

        @Schema(description = "ON_SALE 또는 STOP_SELLING", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = SkuAdminValidationMessage.STATUS)
        ProductSaleStatusFilter saleStatus,

        @Schema(description = "배송 무게(g). null/생략하면 삭제", nullable = true)
        @Positive(message = SkuAdminValidationMessage.WEIGHT)
        @JsonDeserialize(using = IntegerIntDeserializer.class)
        Integer weightGrams,

        @Schema(description = "HS 코드. 점·하이픈·공백 제거 후 숫자 6~10자리. null/빈값/생략하면 삭제", nullable = true)
        @Size(max = 20, message = SkuAdminValidationMessage.HS_CODE)
        String hsCode
    ) {
        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(message = SkuAdminValidationMessage.HS_CODE)
        public boolean isHsCodeValid() {
            return hsCode == null || hsCode.isBlank() || normalizedHsCode().matches("[0-9]{6,10}");
        }

        private String normalizedHsCode() {
            return hsCode.replaceAll("[.\\- ]", "");
        }

        SkuBulkUpdateCommand.Item toCommand() {
            return new SkuBulkUpdateCommand.Item(skuId, additionalPrice.toMoney(), saleStatus.toSaleStatus(), weightGrams,
                hsCode == null || hsCode.isBlank() ? null : HsCode.fromStoredOrNull(normalizedHsCode()));
        }
    }

    @Schema(description = "최소 화폐 단위 정수와 통화")
    public record Price(
        @Schema(description = "금액 (0~100000000)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = SkuAdminValidationMessage.PRICE_AMOUNT)
        @PositiveOrZero(message = SkuAdminValidationMessage.PRICE_AMOUNT)
        @Max(value = 100_000_000, message = SkuAdminValidationMessage.PRICE_AMOUNT)
        @JsonDeserialize(using = IntegerLongDeserializer.class)
        Long amount,

        @Schema(description = "통화", example = "KRW", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = SkuAdminValidationMessage.PRICE_CURRENCY)
        CurrencyCode currency
    ) {
        Money toMoney() {
            return Money.of(amount, currency);
        }
    }

    /** 이 요청의 정수 필드에만 적용한다. Jackson의 소수 → 정수 절삭을 거절하고 기존 정수·문자열 바인딩은 유지한다. */
    public static class IntegerLongDeserializer extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) {
                return context.reportInputMismatch(Long.class, "정수로 입력해 주세요.");
            }
            return context.readValue(parser, Long.class);
        }
    }

    public static class IntegerIntDeserializer extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) {
                return context.reportInputMismatch(Integer.class, "정수로 입력해 주세요.");
            }
            return context.readValue(parser, Integer.class);
        }
    }

}
