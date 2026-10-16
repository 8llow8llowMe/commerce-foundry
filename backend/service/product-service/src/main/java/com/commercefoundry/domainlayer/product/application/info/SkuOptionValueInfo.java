package com.commercefoundry.domainlayer.product.application.info;

/**
 * 서비스 간 SKU 조회의 옵션 한 칸 (주문 항목 스냅숏용 이름).
 */
public record SkuOptionValueInfo(String optionGroupName, String optionValueName) {
}
