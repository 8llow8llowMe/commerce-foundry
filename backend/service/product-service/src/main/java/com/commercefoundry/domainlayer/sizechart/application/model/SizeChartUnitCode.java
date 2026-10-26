package com.commercefoundry.domainlayer.sizechart.application.model;

import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;

/**
 * 사이즈표 단위 입력 ({@code @RequestParam} · 요청 본문). 값은 {@link SizeChartUnit} 과 같다 — 컨트롤러가 domain 타입을 쓰지 않으므로
 * (H1) 입력 타입을 application/model 에 두고 Facade 가 domain 으로 바꾼다.
 */
public enum SizeChartUnitCode {

    CM,
    INCH;

    public SizeChartUnit toDomain() {
        return SizeChartUnit.valueOf(name());
    }
}
