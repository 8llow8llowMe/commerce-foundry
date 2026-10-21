package com.commercefoundry.domainlayer.product.application.model;

import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;

/**
 * 어드민 상품 목록의 판매 가능 상태 조건 ({@code @RequestParam}) — 재고를 반영한 {@link SaleAvailability} 와 값이 같다. 컨트롤러가 domain 타입을 쓰지 않으므로
 * (H1) 입력 타입을 application/model 에 두고 Processor 가 domain 으로 바꾼다.
 */
public enum ProductAvailabilityFilter {

    ON_SALE,
    SOLD_OUT,
    STOP_SELLING;

    public SaleAvailability toSaleAvailability() {
        return SaleAvailability.valueOf(name());
    }
}
