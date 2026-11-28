package com.commercefoundry.domainlayer.product.application.command;

import com.commercefoundry.domainlayer.product.domain.model.ProductImageManagedSource;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.shared.commerce.Money;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

/**
 * 어드민 상품 등록 (#9 E1). 문구는 앞뒤 공백을 뺐고 빈 문구는 null 이다. 옵션 · SKU 구성 · 가격 규칙 · 카테고리 존재는 Processor 가 본다.
 * 이미지 첨부(#9 F2) · 맞춤 제작 설정(#9 PR-B)은 선택이다.
 *
 * @param categoryIds 진열 카테고리 (대표 카테고리는 넣지 않아도 Processor 가 더한다). 중복 없이 받은 순서
 * @param descriptionHtml 정제 전 원문 — Processor 가 정제한다
 * @param optionGroups 배열 순서 = 노출 순서. 옵션 없는 상품이면 빈 목록
 * @param createdByMemberId access 토큰의 memberId (본문으로 받지 않는다)
 * @param customOrder 맞춤 제작 설정. 없으면 {@link ProductCustomOrderCommand#disabled()}
 */
@Builder
public record ProductCreateCommand(
    String name, String summary, String descriptionHtml, long categoryId, List<Long> categoryIds,
    SaleStatus saleStatus, boolean visible, Money salePrice, Money listPrice, Integer maxQuantityPerOrder,
    boolean preorder, LocalDate expectedShipDate, List<OptionGroup> optionGroups, List<Sku> skus, long createdByMemberId, List<Image> images,
    ProductCustomOrderCommand customOrder
) {

    public ProductCreateCommand {
        images = images == null ? List.of() : List.copyOf(images);
        customOrder = customOrder == null ? ProductCustomOrderCommand.disabled() : customOrder;
    }

    /**
     * 첨부할 이미지 — 원본은 둘 중 하나다. 관리 원본이 있으면 업로드 키는 없다. 둘 다 없으면 업로드 키가 없는 것으로 보고 첨부가 400 {@code PRODUCT_032} 로 거절한다.
     *
     * @param uploadKey 어드민이 올린 임시 업로드 키(#9 F2). 요청한 어드민 소유만 붙는다
     * @param managedSource 고정한 상품 미디어 원본(#359 C1) — 서버가 AI 상품 초안에서 만든다. 어드민 요청 본문에서 오지 않는다
     */
    public record Image(String uploadKey, ProductImageManagedSource managedSource,
                        com.commercefoundry.domainlayer.product.domain.model.ImageType imageType, String altText) {

        public Image {
            if (uploadKey != null && managedSource != null) {
                throw new IllegalArgumentException("이미지 원본은 업로드 키와 관리 원본 중 하나여야 합니다.");
            }
        }

        /** 어드민이 올린 임시 업로드 키로 붙이는 이미지(#9 F2). */
        public Image(String uploadKey, com.commercefoundry.domainlayer.product.domain.model.ImageType imageType, String altText) {
            this(uploadKey, null, imageType, altText);
        }

        /** 고정한 상품 미디어 원본으로 붙이는 이미지(#359 C1). */
        public static Image managed(ProductImageManagedSource source, com.commercefoundry.domainlayer.product.domain.model.ImageType imageType, String altText) {
            return new Image(null, java.util.Objects.requireNonNull(source, "source"), imageType, altText);
        }
    }

    /** @param values 값 이름 (배열 순서 = 노출 순서) */
    @Builder
    public record OptionGroup(String name, List<String> values) {
    }

    /**
     * @param optionValues 그룹 순서대로 고른 값 이름 — 옵션 없는 상품이면 빈 목록
     * @param hsCode 점 · 하이픈 · 공백을 뺀 숫자열. 없으면 null
     * @param initialStock 처음 재고. 없으면 재고 행을 만들지 않는다(품절로 생긴다)
     */
    @Builder
    public record Sku(List<String> optionValues, Money additionalPrice, SaleStatus saleStatus, Integer weightGrams, String hsCode, InitialStock initialStock) {
    }

    /** 새 SKU 의 처음 재고 (D6) — 재고 행과 조정 이력(RESTOCK) 1건을 같은 트랜잭션에서 만든다. */
    public record InitialStock(boolean stockManaged, long quantity) {
    }
}
