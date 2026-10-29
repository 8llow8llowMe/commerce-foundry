package com.commercefoundry.domainlayer.product.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.CustomOrderFieldInternalItem;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.SkuInternalItem;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.SkuOptionValueInternalItem;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.SkuInternalResponse;
import com.commercefoundry.commerce.support.web.MoneyItems;
import com.commercefoundry.domainlayer.product.application.info.CustomOrderFieldDefinition;
import com.commercefoundry.domainlayer.product.application.info.SkuInternalInfo;
import com.commercefoundry.domainlayer.product.application.info.SkuOptionValueInfo;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SkuInternalPresenter {

    public SkuInternalResponse toResponse(List<SkuInternalInfo> skus) {
        return SkuInternalResponse.builder()
            .skus(skus.stream().map(this::toItem).toList())
            .build();
    }

    private SkuInternalItem toItem(SkuInternalInfo sku) {
        return SkuInternalItem.builder()
            .skuId(String.valueOf(sku.skuId()))
            .productId(String.valueOf(sku.productId()))
            .productName(sku.productName())
            .optionValues(sku.optionValues().stream().map(this::toOptionValueItem).toList())
            .optionName(sku.optionName())
            .salePrice(MoneyItems.of(sku.salePrice()))
            .additionalPrice(MoneyItems.of(sku.additionalPrice()))
            .saleStatus(sku.saleStatus().toMetadata())
            .purchasable(sku.purchasable())
            .maxQuantityPerOrder(sku.maxQuantityPerOrder())
            .customOrder(sku.customOrder())
            .customOrderFields(sku.customOrderFields().stream().map(this::toCustomOrderField).toList())
            .weightGrams(sku.weightGrams())
            .hsCode(sku.hsCode() == null ? null : sku.hsCode().value())
            .build();
    }

    private CustomOrderFieldInternalItem toCustomOrderField(CustomOrderFieldDefinition field) {
        return CustomOrderFieldInternalItem.builder()
            .fieldKey(field.fieldKey())
            .inputType(field.inputType().name())
            .required(field.required())
            .min(field.min())
            .max(field.max())
            .step(field.step())
            .build();
    }

    private SkuOptionValueInternalItem toOptionValueItem(SkuOptionValueInfo option) {
        return SkuOptionValueInternalItem.builder()
            .optionGroupName(option.optionGroupName())
            .optionValueName(option.optionValueName())
            .build();
    }
}
