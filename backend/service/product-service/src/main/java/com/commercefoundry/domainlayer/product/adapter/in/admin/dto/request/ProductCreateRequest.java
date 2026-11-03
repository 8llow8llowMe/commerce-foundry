package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.product.application.command.ProductCreateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.product.application.model.ProductSaleStatusFilter;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

/**
 * 어드민 상품 등록 요청 (#9 E1). 이미지(#9 F2)는 선택이며 맞춤 제작 입력(#9 E2)은 후속 범위다.
 * 문구는 앞뒤 공백을 빼고 저장하며 빈 요약은 없는 값(null)이다. 등록한 어드민은 토큰의 memberId 다 — 본문으로 받지 않는다.
 *
 * <p>옵션 · SKU: 옵션 그룹마다 값 이름 목록을 보내고, SKU 는 그룹 순서대로 고른 값 이름({@code optionValues})으로 조합을 가리킨다.
 * 옵션 없는 상품은 {@code optionGroups} 를 비우고 SKU 를 한 개({@code optionValues} 빈 배열) 둔다. 구성 · 조합 · 가격 규칙은 Processor 가 본다
 * ({@code PRODUCT_013} · {@code 014} · {@code 016}).
 *
 * <p>교차 검증은 {@code @AssertTrue} 메서드다. 오류의 {@code field} 는 메서드 이름에서 온다.
 */
@Builder
@Schema(description = "어드민 상품 등록 요청")
public record ProductCreateRequest(

    @Schema(description = "상품명 (1~100자, 제어 · 서식 문자 불가)", example = "린넨 생활한복 저고리", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = ProductValidationMessage.PRODUCT_NAME_REQUIRED)
    @Size(max = 100, message = ProductValidationMessage.PRODUCT_NAME_SIZE_MAX)
    @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = ProductValidationMessage.PRODUCT_NAME_INVALID_CHARACTER)
    String name,

    @Schema(description = "한 줄 요약 (200자 이하, 제어 · 서식 문자 불가)", example = "여름에 시원한 린넨 저고리", nullable = true)
    @Size(max = 200, message = ProductValidationMessage.PRODUCT_SUMMARY_INVALID)
    @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = ProductValidationMessage.PRODUCT_SUMMARY_INVALID)
    String summary,

    @Schema(description = "상세 설명 HTML (200000자 이하). 허용 목록 밖의 태그 · 속성 · 주소는 빼고 저장한다 — 응답 descriptionHtmlSanitized 로 알린다", nullable = true)
    @Size(max = 200_000, message = ProductValidationMessage.PRODUCT_DESCRIPTION_HTML_SIZE_MAX)
    String descriptionHtml,

    @Schema(description = "대표 카테고리 아이디 — 문자열로 보내도 된다. 없는 카테고리는 400(PRODUCT_012)", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = ProductValidationMessage.PRODUCT_CATEGORY_ID_REQUIRED)
    @Positive(message = ProductValidationMessage.CATEGORY_ID_POSITIVE)
    Long categoryId,

    @Schema(description = "진열 카테고리 아이디 (최대 20개). 대표 카테고리가 없으면 더한다. 중복은 하나로 친다", nullable = true)
    @Size(max = 20, message = ProductValidationMessage.PRODUCT_CATEGORY_IDS_SIZE_MAX)
    List<@NotNull(message = ProductValidationMessage.PRODUCT_CATEGORY_IDS_ELEMENT_REQUIRED)
        @Positive(message = ProductValidationMessage.CATEGORY_ID_POSITIVE) Long> categoryIds,

    @Schema(description = "판매 상태 — ON_SALE(판매중) · STOP_SELLING(판매 종료)", example = "ON_SALE", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = ProductValidationMessage.PRODUCT_SALE_STATUS_REQUIRED)
    ProductSaleStatusFilter saleStatus,

    @Schema(description = "스토어프론트 노출 여부", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = ProductValidationMessage.PRODUCT_VISIBLE_REQUIRED)
    Boolean visible,

    @Schema(description = "기본 판매가 — 판매중 SKU 의 최저가(추가금 0 인 SKU 의 가격)다", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = ProductValidationMessage.PRODUCT_PRICE_REQUIRED)
    @Valid
    MoneyRequest salePrice,

    @Schema(description = "정가 (할인 전). 통화는 판매가와 같아야 한다. 없으면 할인 표시 없음", nullable = true)
    @Valid
    MoneyRequest listPrice,

    @Schema(description = "1회 주문 최대 수량 (1~999). 없으면 주문 엔진 기본값(10, order-flow-guide.md §2-3)이 걸린다 — 재고가 적은 상품은 더 작게 둔다", example = "5", nullable = true)
    @Min(value = 1, message = ProductValidationMessage.PRODUCT_MAX_QUANTITY_PER_ORDER_RANGE)
    @Max(value = 999, message = ProductValidationMessage.PRODUCT_MAX_QUANTITY_PER_ORDER_RANGE)
    Integer maxQuantityPerOrder,

    @Schema(description = "프리오더(예약 판매) 상품 여부. 없으면 false", example = "false", nullable = true)
    Boolean preorder,

    @Schema(description = "발송 예정일 (yyyy-MM-dd, 오늘 이후). preorder 가 true 일 때만 보낸다 — 프리오더인데 없으면 \"발송일 추후 안내\"", example = "2026-12-01", nullable = true)
    @FutureOrPresent(message = ProductValidationMessage.PRODUCT_EXPECTED_SHIP_DATE_PAST)
    LocalDate expectedShipDate,

    @Schema(description = "옵션 그룹 (최대 5개, 배열 순서 = 노출 순서). 옵션 없는 상품이면 비운다", nullable = true)
    @Size(max = 5, message = ProductValidationMessage.PRODUCT_OPTION_GROUPS_SIZE_MAX)
    List<@NotNull(message = ProductValidationMessage.PRODUCT_OPTION_GROUP_REQUIRED) @Valid OptionGroupRequest> optionGroups,

    @Schema(description = "SKU (1~100개). 옵션 없는 상품은 한 개", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = ProductValidationMessage.PRODUCT_SKUS_SIZE)
    @Size(min = 1, max = 100, message = ProductValidationMessage.PRODUCT_SKUS_SIZE)
    List<@NotNull(message = ProductValidationMessage.PRODUCT_SKU_REQUIRED) @Valid SkuRequest> skus,

    @Schema(description = "발급받아 업로드한 정지 이미지. 최대 8장, 대표는 한 장. 배열 순서로 노출한다", nullable = true)
    @Size(max = 8, message = ProductValidationMessage.PRODUCT_IMAGES_INVALID)
    List<@NotNull(message = ProductValidationMessage.PRODUCT_IMAGES_INVALID) @Valid ImageRequest> images,

    @Schema(description = "맞춤 제작 설정. 생략하면 맞춤 제작 꺼짐 · 입력 항목 없음", nullable = true)
    @Valid
    CustomOrderSettingsRequest customOrder
) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = ProductValidationMessage.PRODUCT_EXPECTED_SHIP_DATE_REQUIRES_PREORDER)
    public boolean isExpectedShipDateWithPreorder() {
        return expectedShipDate == null || Boolean.TRUE.equals(preorder);
    }

    /** @param createdByMemberId access 토큰의 memberId */
    public ProductCreateCommand toCommand(long createdByMemberId) {
        return ProductCreateCommand.builder()
            .name(name.strip())
            .summary(summary == null || summary.isBlank() ? null : summary.strip())
            .descriptionHtml(descriptionHtml)
            .categoryId(categoryId)
            .categoryIds(categoryIds == null ? List.of() : List.copyOf(categoryIds))
            .saleStatus(saleStatus.toSaleStatus())
            .visible(visible)
            .salePrice(salePrice.toMoney())
            .listPrice(listPrice == null ? null : listPrice.toMoney())
            .maxQuantityPerOrder(maxQuantityPerOrder)
            .preorder(Boolean.TRUE.equals(preorder))
            .expectedShipDate(expectedShipDate)
            .optionGroups(optionGroups == null ? List.of() : optionGroups.stream().map(OptionGroupRequest::toCommand).toList())
            .skus(skus.stream().map(SkuRequest::toCommand).toList())
            .images(images == null ? List.of() : images.stream().map(ImageRequest::toCommand).toList())
            .createdByMemberId(createdByMemberId)
            .customOrder(customOrder == null ? null : customOrder.toCommand())
            .build();
    }

    @Schema(description = "등록 이미지 — VIDEO 는 후속 범위")
    public record ImageRequest(
        @Schema(description = "현재 어드민에게 발급된 uploads 키", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = ProductValidationMessage.PRODUCT_IMAGES_INVALID)
        @Size(max = 100, message = ProductValidationMessage.PRODUCT_IMAGES_INVALID)
        String uploadKey,

        @Schema(description = "THUMBNAIL 또는 GALLERY", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = ProductValidationMessage.PRODUCT_IMAGES_INVALID)
        com.commercefoundry.domainlayer.product.domain.model.ImageType imageType,

        @Schema(description = "대체 텍스트. 없으면 화면 낭독기가 건너뛴다", nullable = true)
        @Size(max = 200, message = ProductValidationMessage.PRODUCT_IMAGES_INVALID)
        @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = ProductValidationMessage.PRODUCT_IMAGES_INVALID)
        String altText
    ) {
        ProductCreateCommand.Image toCommand() {
            return new ProductCreateCommand.Image(uploadKey, imageType, altText == null || altText.isBlank() ? null : altText.strip());
        }
    }

    @Schema(description = "옵션 그룹")
    public record OptionGroupRequest(

        @Schema(description = "그룹 이름 (1~30자, 상품 안에서 겹치지 않게)", example = "사이즈", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = ProductValidationMessage.PRODUCT_OPTION_GROUP_NAME_INVALID)
        @Size(max = 30, message = ProductValidationMessage.PRODUCT_OPTION_GROUP_NAME_INVALID)
        @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = ProductValidationMessage.PRODUCT_OPTION_GROUP_NAME_INVALID)
        String name,

        @Schema(description = "값 이름 (1~50개, 원소 1~50자, 그룹 안에서 겹치지 않게, 배열 순서 = 노출 순서)", example = "[\"S\", \"M\", \"L\"]",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = ProductValidationMessage.PRODUCT_OPTION_VALUES_SIZE)
        @Size(min = 1, max = 50, message = ProductValidationMessage.PRODUCT_OPTION_VALUES_SIZE)
        List<@NotBlank(message = ProductValidationMessage.PRODUCT_OPTION_VALUE_NAME_INVALID)
            @Size(max = 50, message = ProductValidationMessage.PRODUCT_OPTION_VALUE_NAME_INVALID)
            @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = ProductValidationMessage.PRODUCT_OPTION_VALUE_NAME_INVALID) String> values
    ) {

        ProductCreateCommand.OptionGroup toCommand() {
            return new ProductCreateCommand.OptionGroup(name.strip(), values.stream().map(String::strip).toList());
        }
    }

    @Schema(description = "SKU — 옵션 값 조합 하나")
    public record SkuRequest(

        @Schema(description = "그룹 순서대로 고른 값 이름 (최대 5개). 옵션 없는 상품은 빈 배열", example = "[\"M\"]", nullable = true)
        @Size(max = 5, message = ProductValidationMessage.PRODUCT_SKU_OPTION_VALUES_INVALID)
        List<@NotBlank(message = ProductValidationMessage.PRODUCT_SKU_OPTION_VALUES_INVALID)
            @Size(max = 50, message = ProductValidationMessage.PRODUCT_SKU_OPTION_VALUES_INVALID)
            @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = ProductValidationMessage.PRODUCT_SKU_OPTION_VALUES_INVALID) String> optionValues,

        @Schema(description = "옵션 추가금 (0 이상, 통화는 판매가와 같게)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = ProductValidationMessage.PRODUCT_PRICE_REQUIRED)
        @Valid
        MoneyRequest additionalPrice,

        @Schema(description = "판매 상태 — ON_SALE · STOP_SELLING", example = "ON_SALE", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = ProductValidationMessage.PRODUCT_SALE_STATUS_REQUIRED)
        ProductSaleStatusFilter saleStatus,

        @Schema(description = "배송 무게 (그램, 1 이상). 해외 판매 상품만 필수", example = "350", nullable = true)
        @Positive(message = ProductValidationMessage.PRODUCT_SKU_WEIGHT_GRAMS_POSITIVE)
        Integer weightGrams,

        @Schema(description = "통관 신고용 HS 코드 — 점 · 하이픈 · 공백은 빼고 저장한다(숫자 6~10자리). 해외 판매 상품만 필수", example = "6211.42-1000", nullable = true)
        @Size(max = 20, message = ProductValidationMessage.PRODUCT_SKU_HS_CODE_INVALID)
        String hsCode,

        @Schema(description = "처음 재고. 없으면 재고를 등록하지 않아 품절로 생긴다 — 나중에 재고 화면에서 등록한다", nullable = true)
        @Valid
        InitialStockRequest initialStock
    ) {

        private static final java.util.regex.Pattern HS_CODE_SEPARATORS = java.util.regex.Pattern.compile("[.\\- ]");
        private static final java.util.regex.Pattern HS_CODE_DIGITS = java.util.regex.Pattern.compile("[0-9]{6,10}");

        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(message = ProductValidationMessage.PRODUCT_SKU_HS_CODE_INVALID)
        public boolean isHsCodeValid() {
            return hsCode == null || hsCode.isBlank() || HS_CODE_DIGITS.matcher(normalizedHsCode()).matches();
        }

        ProductCreateCommand.Sku toCommand() {
            return ProductCreateCommand.Sku.builder()
                .optionValues(optionValues == null ? List.of() : optionValues.stream().map(String::strip).toList())
                .additionalPrice(additionalPrice.toMoney())
                .saleStatus(saleStatus.toSaleStatus())
                .weightGrams(weightGrams)
                .hsCode(hsCode == null || hsCode.isBlank() ? null : normalizedHsCode())
                .initialStock(initialStock == null ? null : new ProductCreateCommand.InitialStock(initialStock.stockManaged(), initialStock.quantity()))
                .build();
        }

        /** 점 · 하이픈 · 공백만 뺀다 — 전각 숫자 등은 고치지 않고 형식 오류로 둔다(#9 설계). */
        private String normalizedHsCode() {
            return HS_CODE_SEPARATORS.matcher(hsCode).replaceAll("");
        }
    }

    @Schema(description = "처음 재고 — 재고 행과 조정 이력(입고) 1건을 함께 만든다")
    public record InitialStockRequest(

        @Schema(description = "재고를 관리하는가. false 면 무제한 판매", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = ProductValidationMessage.PRODUCT_INITIAL_STOCK_INVALID)
        Boolean stockManaged,

        @Schema(description = "판매 가능 수량 (0 이상)", example = "30", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = ProductValidationMessage.PRODUCT_INITIAL_STOCK_INVALID)
        @PositiveOrZero(message = ProductValidationMessage.STOCK_QUANTITY_MIN)
        Long quantity
    ) {
    }

    @Schema(description = "금액 — 최소 화폐 단위 정수와 통화")
    public record MoneyRequest(

        @Schema(description = "금액 (최소 화폐 단위, 0 이상 100000000 이하). KRW 는 원, USD 는 센트", example = "59000", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = ProductValidationMessage.PRODUCT_MONEY_AMOUNT_REQUIRED)
        @PositiveOrZero(message = ProductValidationMessage.PRODUCT_MONEY_AMOUNT_MIN)
        @Max(value = MAX_AMOUNT, message = ProductValidationMessage.PRODUCT_MONEY_AMOUNT_MAX)
        Long amount,

        @Schema(description = "통화 (ISO 4217)", example = "KRW", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = ProductValidationMessage.PRODUCT_MONEY_CURRENCY_REQUIRED)
        CurrencyCode currency
    ) {

        /** 금액 상한 (최소 화폐 단위) — 판매가 + 추가금 덧셈 · 주문 금액 곱셈이 long 을 넘지 않게 (#9 설계 §1). */
        static final long MAX_AMOUNT = 100_000_000L;

        Money toMoney() {
            return Money.of(amount, currency);
        }
    }
}
