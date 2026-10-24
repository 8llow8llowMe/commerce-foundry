package com.commercefoundry.domainlayer.migration.domain.model;

import java.util.Objects;

/**
 * 정규화한 상품 기본 칸 (#19 첫 PR — 옵션 · 이미지 · 카테고리는 결정 D3 · D4 · D5 · D8 뒤에 더한다). 값은 이 엔진의 칼럼 규칙을 지킨다.
 *
 * @param rowNumber 원본 데이터 행 번호
 * @param sourceKey 원본 상품코드 — 재실행 멱등의 업무 키
 * @param salePrice 원본 기본 판매가(원). 옵션가를 반영한 기본가 이동은 {@link OptionPricing} 이 한다
 * @param listPrice 원본 정가(원). 없으면 null
 * @param weightGrams 그램. 없거나 규칙 밖이면 null
 * @param hsCode 숫자 6~10자리. 없거나 규칙 밖이면 null
 */
public record SourceProductRecord(int rowNumber, String sourceKey, String name, long salePrice, Long listPrice, Integer weightGrams, String hsCode) {

    public SourceProductRecord {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(name, "name");
    }

    /** 상품명 · 가격은 개인정보가 아니지만 리포트 규칙(값 없음)을 맞춘다 — 행 번호와 원본 키만. */
    @Override
    public String toString() {
        return "SourceProductRecord[rowNumber=" + rowNumber + ", sourceKey=" + sourceKey + "]";
    }
}
