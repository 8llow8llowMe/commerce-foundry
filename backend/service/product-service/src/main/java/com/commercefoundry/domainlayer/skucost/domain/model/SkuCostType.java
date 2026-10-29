package com.commercefoundry.domainlayer.skucost.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * SKU 원가 유형. 유형마다 적용 시작 시각 순의 타임라인이 따로 있고, 한 시각의 원가는 유형별로 하나다.
 * 응답은 {@code {code, name, description}} 메타데이터로 내린다 ({@code coding-conventions.md} §11).
 */
@Getter
@RequiredArgsConstructor
public enum SkuCostType implements CodeNameDescribable {

    GOODS("매입 · 제작 원가", "상품 한 개를 매입하거나 제작하는 데 든 원가입니다."),
    PACKAGING("포장 변동비", "상품 한 개를 포장하는 데 드는 변동비입니다.");

    private final String displayName;
    private final String description;
}
