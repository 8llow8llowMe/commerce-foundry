package com.commercefoundry.domainlayer.sizechart.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 저장 단위와 표시 단위가 다를 때만 고친다. 1 인치 = 2.54 센티미터, 결과는 소수 둘째 자리 {@link RoundingMode#HALF_UP} 이다.
 * 같은 단위면 값을 그대로 돌려준다 — scale 을 다시 매기지 않는다.
 */
public final class SizeChartUnits {

    public static final BigDecimal CM_PER_INCH = new BigDecimal("2.54");

    private SizeChartUnits() {
    }

    public static BigDecimal toDisplay(BigDecimal stored, SizeChartUnit storedUnit, SizeChartUnit displayUnit) {
        if (storedUnit == displayUnit) {
            return stored;
        }
        if (storedUnit == SizeChartUnit.CM) {
            return stored.divide(CM_PER_INCH, SizeChartLimits.MEASUREMENT_SCALE, RoundingMode.HALF_UP);
        }
        return stored.multiply(CM_PER_INCH).setScale(SizeChartLimits.MEASUREMENT_SCALE, RoundingMode.HALF_UP);
    }
}
