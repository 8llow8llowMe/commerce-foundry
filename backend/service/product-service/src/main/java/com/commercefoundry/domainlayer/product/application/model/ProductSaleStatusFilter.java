package com.commercefoundry.domainlayer.product.application.model;

import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;

/**
 * 어드민 상품 목록의 저장된 판매 상태 조건 ({@code @RequestParam}). 값은 {@link SaleStatus} 와 같다 — 컨트롤러가 domain 타입을 쓰지 않으므로
 * (H1) 입력 타입을 application/model 에 두고 Processor 가 domain 으로 바꾼다.
 */
public enum ProductSaleStatusFilter {

    ON_SALE,
    STOP_SELLING;

    public SaleStatus toSaleStatus() {
        return SaleStatus.valueOf(name());
    }
}
