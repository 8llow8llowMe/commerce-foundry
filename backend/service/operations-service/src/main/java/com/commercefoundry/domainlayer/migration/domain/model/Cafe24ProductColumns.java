package com.commercefoundry.domainlayer.migration.domain.model;

import java.util.Objects;

/**
 * 카페24 상품 내보내기의 컬럼 이름. 실제 이름은 내보내기 샘플(D3) 확인 전이라 코드에 고정하지 않는다 — 설정({@code migration.cafe24.columns.*})이 채운다.
 *
 * @param listPrice 정가 컬럼. 없으면 null
 * @param weight 무게 컬럼. 없으면 null
 * @param hsCode HS 코드 컬럼. 없으면 null
 */
public record Cafe24ProductColumns(String productCode, String name, String salePrice, String listPrice, String weight, WeightUnit weightUnit, String hsCode) {

    public Cafe24ProductColumns {
        Objects.requireNonNull(productCode, "productCode");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(salePrice, "salePrice");
        Objects.requireNonNull(weightUnit, "weightUnit");
    }
}
