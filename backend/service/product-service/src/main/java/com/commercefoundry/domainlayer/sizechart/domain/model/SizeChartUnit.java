package com.commercefoundry.domainlayer.sizechart.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 사이즈표 치수의 저장 · 표시 단위. 저장은 템플릿의 기본 단위 그대로이고, 다른 단위로 바꾸는 것은 응답에서만 한다 ({@link SizeChartUnits}).
 */
@Getter
@RequiredArgsConstructor
public enum SizeChartUnit implements CodeNameDescribable {

    CM("센티미터", "치수를 센티미터로 둡니다."),
    INCH("인치", "치수를 인치로 둡니다.");

    private final String displayName;
    private final String description;
}
