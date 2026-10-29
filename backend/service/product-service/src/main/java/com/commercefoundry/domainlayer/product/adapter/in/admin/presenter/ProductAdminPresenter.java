package com.commercefoundry.domainlayer.product.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductListAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductOptionGroupAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductOptionValueAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductSkuAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductImageAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.CustomOrderFieldAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductDetailAdminResponse;
import com.commercefoundry.domainlayer.product.application.info.ProductAdminDetailInfo;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.OptionGroup;
import com.commercefoundry.domainlayer.product.domain.model.OptionValue;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderField;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.commercefoundry.commerce.support.web.MoneyItems;
import com.commercefoundry.domainlayer.product.application.info.ProductAdminListItemInfo;
import com.commercefoundry.persistence.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * 고객 상품 Presenter({@code ProductWebPresenter})와 DTO 를 공유하지 않는다 — 어드민에만 숨김 · 저장된 판매 상태 · SKU 수 · 수정 시각이 있다.
 */
@Component
public class ProductAdminPresenter {

    public ProductDetailAdminResponse toDetailResponse(ProductAdminDetailInfo info) {
        Product product = info.product();
        Map<Long, List<OptionValue>> valuesByGroup = info.optionValues().stream().collect(Collectors.groupingBy(OptionValue::optionGroupId));
        return ProductDetailAdminResponse.builder()
            .productId(String.valueOf(product.id())).categoryId(String.valueOf(product.categoryId()))
            .categoryIds(info.categoryIds().stream().map(String::valueOf).toList())
            .name(product.name()).summary(product.summary()).descriptionHtml(product.descriptionHtml())
            .saleStatus(product.saleStatus().toMetadata()).visible(product.visible())
            .salePrice(MoneyItems.of(product.salePrice())).listPrice(MoneyItems.of(product.listPrice()))
            .maxQuantityPerOrder(product.maxQuantityPerOrder()).customOrder(product.customOrder())
            .customOrderNotice(product.customOrderNotice()).customOrderLeadTimeDays(product.customOrderLeadTimeDays())
            .preorder(product.preorder()).expectedShipDate(product.expectedShipDate()).version(product.version())
            .createdByMemberId(product.createdByMemberId() == null ? null : String.valueOf(product.createdByMemberId()))
            .optionGroups(info.optionGroups().stream().map(group -> toGroupItem(group, valuesByGroup.getOrDefault(group.id(), List.of()))).toList())
            .skus(info.skus().stream().map(this::toSkuItem).toList())
            .images(info.images().stream().map(this::toImageItem).toList())
            .customOrderFields(info.customOrderFields().stream().map(this::toCustomFieldItem).toList())
            .build();
    }

    private ProductOptionGroupAdminItem toGroupItem(OptionGroup group, List<OptionValue> values) {
        return new ProductOptionGroupAdminItem(String.valueOf(group.id()), group.name(), group.displayOrder(), group.retiredAt(),
            values.stream().map(value -> new ProductOptionValueAdminItem(
                String.valueOf(value.id()), value.name(), value.displayOrder(), value.retiredAt()
            )).toList());
    }

    private ProductSkuAdminItem toSkuItem(Sku sku) {
        return new ProductSkuAdminItem(String.valueOf(sku.id()), sku.optionKey().valueIds().stream().map(String::valueOf).toList(),
            MoneyItems.of(sku.additionalPrice()), sku.saleStatus().toMetadata(), sku.weightGrams(), sku.hsCode() == null ? null : sku.hsCode().value());
    }

    private ProductImageAdminItem toImageItem(ProductImage image) {
        CodeNameDescriptionMetadata type = switch (image.imageType()) {
            case THUMBNAIL -> CodeNameDescriptionMetadata.of("THUMBNAIL", "대표 이미지", "상품 목록의 대표 이미지입니다.");
            case GALLERY -> CodeNameDescriptionMetadata.of("GALLERY", "갤러리 이미지", "상품 상세의 정지 이미지입니다.");
            case VIDEO -> CodeNameDescriptionMetadata.of("VIDEO", "영상", "상품 상세의 영상입니다.");
        };
        return new ProductImageAdminItem(String.valueOf(image.id()), type, image.url(), image.width(), image.height(),
            image.altText(), image.displayOrder(), image.posterUrl());
    }

    private CustomOrderFieldAdminItem toCustomFieldItem(CustomOrderField field) {
        CodeNameDescriptionMetadata type = switch (field.inputType()) {
            case NUMBER -> CodeNameDescriptionMetadata.of("NUMBER", "숫자", "최소 · 최대 · 증분을 사용하는 숫자 입력입니다.");
            case TEXT -> CodeNameDescriptionMetadata.of("TEXT", "자유 입력", "최대 글자 수를 사용하는 문자 입력입니다.");
        };
        return new CustomOrderFieldAdminItem(field.fieldKey(), field.label(), type, field.unit(), field.required(),
            field.minValue(), field.maxValue(), field.stepValue(), field.helpText(), field.displayOrder());
    }

    /** 페이지 정보(전체 건수 포함)는 그대로 두고 항목만 바꾼다. */
    public PageResponse<ProductListAdminItem> toListPageResponse(Page<ProductAdminListItemInfo> products) {
        return PageResponse.of(products).map(this::toListItem);
    }

    private ProductListAdminItem toListItem(ProductAdminListItemInfo product) {
        return ProductListAdminItem.builder()
            .productId(String.valueOf(product.productId()))
            .name(product.name())
            .thumbnailUrl(product.thumbnailUrl())
            .salePrice(MoneyItems.of(product.salePrice()))
            .listPrice(MoneyItems.of(product.listPrice()))
            .saleStatus(product.saleStatus().toMetadata())
            .visible(product.visible())
            .availability(product.availability().toMetadata())
            .skuCount(product.skuCount())
            .updatedAt(product.updatedAt())
            .build();
    }
}
