package com.commercefoundry.domainlayer.cart.adapter.in.web.presenter;

import com.commercefoundry.commerce.support.web.MoneyItems;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.item.CartLineItem;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.item.CartMergeSkippedItem;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.item.CustomOptionItem;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.response.CartMergeResponse;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.response.CartResponse;
import com.commercefoundry.domainlayer.cart.application.info.CartInfo;
import com.commercefoundry.domainlayer.cart.application.info.CartLineInfo;
import com.commercefoundry.domainlayer.cart.application.info.CartMergeInfo;
import com.commercefoundry.domainlayer.cart.application.model.CartMergeSkip;
import com.commercefoundry.domainlayer.cart.domain.model.CustomOption;
import org.springframework.stereotype.Component;

@Component
public class CartWebPresenter {

    public CartResponse toResponse(CartInfo cart) {
        return CartResponse.builder()
            .items(cart.lines().stream().map(this::toLineItem).toList())
            .totalAmount(MoneyItems.of(cart.totalAmount()))
            .purchasableCount(cart.purchasableCount())
            .build();
    }

    public CartMergeResponse toMergeResponse(CartMergeInfo merge) {
        return CartMergeResponse.builder()
            .cart(toResponse(merge.cart()))
            .skippedItems(merge.skippedItems().stream().map(this::toSkippedItem).toList())
            .build();
    }

    private CartMergeSkippedItem toSkippedItem(CartMergeSkip skip) {
        return CartMergeSkippedItem.builder()
            .index(skip.index())
            .skuId(String.valueOf(skip.skuId()))
            .reason(skip.reason().name())
            .build();
    }

    private CartLineItem toLineItem(CartLineInfo line) {
        return CartLineItem.builder()
            .itemId(String.valueOf(line.itemId()))
            .skuId(String.valueOf(line.skuId()))
            .productId(line.productId() == null ? null : String.valueOf(line.productId()))
            .productName(line.productName())
            .optionName(line.optionName())
            .quantity(line.quantity())
            .unitPrice(MoneyItems.of(line.unitPrice()))
            .lineAmount(MoneyItems.of(line.lineAmount()))
            .status(line.status().toMetadata())
            .maxQuantityPerOrder(line.maxQuantityPerOrder())
            .customOrder(line.customOrder())
            .customOptions(line.customOptions().stream().map(this::toCustomOptionItem).toList())
            .quantityExceeded(line.quantityExceeded())
            .build();
    }

    private CustomOptionItem toCustomOptionItem(CustomOption option) {
        return CustomOptionItem.builder()
            .fieldKey(option.fieldKey())
            .value(option.value())
            .build();
    }
}
