package com.commercefoundry.domainlayer.product.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.product.adapter.in.support.MoneyItems;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.CategoryPathItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.CustomOrderFieldItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.CustomOrderItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductImageItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductOptionGroupItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductOptionValueItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductSkuItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductThumbnailItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.response.ProductDetailResponse;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.response.ProductListItemResponse;
import com.commercefoundry.domainlayer.product.application.info.CategoryPathInfo;
import com.commercefoundry.domainlayer.product.application.info.CustomOrderFieldInfo;
import com.commercefoundry.domainlayer.product.application.info.CustomOrderInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductDetailInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductImageInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductListItemInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductOptionGroupInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductOptionValueInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductSkuInfo;
import com.commercefoundry.persistence.dto.SliceResponse;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

/**
 * 고객 상품 응답. 식별자 {@code long → String}, 금액 {@code Money → MoneyItem}, 판매 상태 → metadata 변환만 한다.
 */
@Component
public class ProductWebPresenter {

    public SliceResponse<ProductListItemResponse> toListResponse(Slice<ProductListItemInfo> slice) {
        return SliceResponse.of(slice).map(this::toListItem);
    }

    public ProductDetailResponse toDetailResponse(ProductDetailInfo info) {
        return ProductDetailResponse.builder()
            .productId(String.valueOf(info.productId()))
            .name(info.name())
            .summary(info.summary())
            .categoryPath(info.categoryPath().stream().map(this::toCategoryPathItem).toList())
            .thumbnail(toThumbnailItem(info.thumbnail()))
            .images(info.images().stream().map(this::toImageItem).toList())
            .descriptionHtml(info.descriptionHtml())
            .salePrice(MoneyItems.of(info.salePrice()))
            .listPrice(MoneyItems.of(info.listPrice()))
            .discountRate(info.discountRate())
            .saleStatus(info.saleStatus().toMetadata())
            .maxQuantityPerOrder(info.maxQuantityPerOrder())
            .optionGroups(info.optionGroups().stream().map(this::toOptionGroupItem).toList())
            .skus(info.skus().stream().map(this::toSkuItem).toList())
            .customOrder(toCustomOrderItem(info.customOrder()))
            .build();
    }

    private ProductListItemResponse toListItem(ProductListItemInfo info) {
        return ProductListItemResponse.builder()
            .productId(String.valueOf(info.productId()))
            .name(info.name())
            .thumbnail(toThumbnailItem(info.thumbnail()))
            .salePrice(MoneyItems.of(info.salePrice()))
            .listPrice(MoneyItems.of(info.listPrice()))
            .discountRate(info.discountRate())
            .hasOptionSurcharge(info.hasOptionSurcharge())
            .saleStatus(info.saleStatus().toMetadata())
            .customOrder(info.customOrder())
            .build();
    }

    private ProductThumbnailItem toThumbnailItem(ProductImageInfo image) {
        if (image == null) {
            return null;
        }
        return ProductThumbnailItem.builder()
            .imageId(String.valueOf(image.imageId()))
            .url(image.url())
            .width(image.width())
            .height(image.height())
            .altText(image.altText())
            .build();
    }

    private ProductImageItem toImageItem(ProductImageInfo image) {
        return ProductImageItem.builder()
            .imageId(String.valueOf(image.imageId()))
            .url(image.url())
            .width(image.width())
            .height(image.height())
            .altText(image.altText())
            .displayOrder(image.displayOrder())
            .build();
    }

    private CategoryPathItem toCategoryPathItem(CategoryPathInfo category) {
        return CategoryPathItem.builder()
            .categoryId(String.valueOf(category.categoryId()))
            .name(category.name())
            .build();
    }

    private ProductOptionGroupItem toOptionGroupItem(ProductOptionGroupInfo group) {
        return ProductOptionGroupItem.builder()
            .optionGroupId(String.valueOf(group.optionGroupId()))
            .name(group.name())
            .displayOrder(group.displayOrder())
            .values(group.values().stream().map(this::toOptionValueItem).toList())
            .build();
    }

    private ProductOptionValueItem toOptionValueItem(ProductOptionValueInfo value) {
        return ProductOptionValueItem.builder()
            .optionValueId(String.valueOf(value.optionValueId()))
            .name(value.name())
            .displayOrder(value.displayOrder())
            .build();
    }

    private ProductSkuItem toSkuItem(ProductSkuInfo sku) {
        return ProductSkuItem.builder()
            .skuId(String.valueOf(sku.skuId()))
            .optionValueIds(sku.optionValueIds().stream().map(String::valueOf).toList())
            .additionalPrice(MoneyItems.of(sku.additionalPrice()))
            .salePrice(MoneyItems.of(sku.salePrice()))
            .saleStatus(sku.saleStatus().toMetadata())
            .build();
    }

    private CustomOrderItem toCustomOrderItem(CustomOrderInfo customOrder) {
        if (customOrder == null) {
            return null;
        }
        return CustomOrderItem.builder()
            .notice(customOrder.notice())
            .leadTimeDays(customOrder.leadTimeDays())
            .fields(customOrder.fields().stream().map(this::toCustomOrderFieldItem).toList())
            .build();
    }

    private CustomOrderFieldItem toCustomOrderFieldItem(CustomOrderFieldInfo field) {
        return CustomOrderFieldItem.builder()
            .fieldKey(field.fieldKey())
            .label(field.label())
            .inputType(field.inputType().name())
            .unit(field.unit())
            .required(field.required())
            .min(field.min())
            .max(field.max())
            .step(field.step())
            .helpText(field.helpText())
            .build();
    }
}
