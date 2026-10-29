package com.commercefoundry.domainlayer.product.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductListAdminItem;
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
