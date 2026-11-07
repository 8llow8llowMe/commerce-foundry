package com.commercefoundry.domainlayer.sizechart.domain.model;

/**
 * 사이즈표 템플릿. 항목(열)은 별도 값이고 이 레코드는 머리만 가진다.
 */
public record SizeChartTemplate(long id, String name, SizeChartUnit baseUnit, long revision) {

    /** 새 템플릿의 업무 리비전은 0이다. 저장된 리비전은 조회 시에만 전달한다. */
    public SizeChartTemplate(long id, String name, SizeChartUnit baseUnit) {
        this(id, name, baseUnit, 0L);
    }
}
