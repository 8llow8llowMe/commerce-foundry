package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;

/**
 * 상세 화면의 SKU 한 건.
 *
 * @param optionValueIds 옵션 값 아이디 — 값이 속한 옵션 그룹의 (노출 순서, 아이디) 순. 옵션이 없으면 빈 목록
 * @param salePrice 기본 판매가 + 추가금
 */
public record ProductSkuInfo(long skuId, List<Long> optionValueIds, Money additionalPrice, Money salePrice, SaleAvailability saleStatus) {

    public ProductSkuInfo {
        optionValueIds = List.copyOf(optionValueIds);
    }
}
