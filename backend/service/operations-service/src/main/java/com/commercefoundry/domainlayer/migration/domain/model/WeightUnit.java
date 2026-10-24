package com.commercefoundry.domainlayer.migration.domain.model;

import java.math.BigDecimal;

/** 원본 무게 칸의 단위. 원본 형식(D3)이 정해지면 설정으로 고른다. */
public enum WeightUnit {

    GRAM(BigDecimal.ONE),
    KILOGRAM(BigDecimal.valueOf(1000));

    private final BigDecimal gramsPerUnit;

    WeightUnit(BigDecimal gramsPerUnit) {
        this.gramsPerUnit = gramsPerUnit;
    }

    public BigDecimal toGrams(BigDecimal amount) {
        return amount.multiply(gramsPerUnit);
    }
}
