package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence;

/**
 * 템플릿별 항목 수. JPQL 생성자 식의 대상이라 저장소 안에 둔다.
 */
public record TemplateColumnCountRow(long templateId, long columnCount) {
}
