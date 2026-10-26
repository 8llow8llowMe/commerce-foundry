package com.commercefoundry.domainlayer.sizechart.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 저장 단위와 표시 단위가 같을 때는 값을 그대로 두고, 다를 때만 1 인치 = 2.54 센티미터로 소수 둘째 자리 반올림한다.
 */
class SizeChartUnitsTest {

    @Test
    @DisplayName("같은 단위면 변환하지 않는다 — 인스턴스와 scale 을 그대로 둔다")
    void sameUnitIsIdentity() {
        BigDecimal stored = new BigDecimal("1.010");

        assertThat(SizeChartUnits.toDisplay(stored, SizeChartUnit.CM, SizeChartUnit.CM)).isSameAs(stored);
        assertThat(SizeChartUnits.toDisplay(stored, SizeChartUnit.INCH, SizeChartUnit.INCH)).isSameAs(stored);
        assertThat(stored.scale()).isEqualTo(3);
    }

    @Test
    @DisplayName("CM → INCH 는 2.54 로 나누고 소수 둘째 자리에서 HALF_UP 한다")
    void centimetersToInches() {
        assertThat(SizeChartUnits.toDisplay(new BigDecimal("2.54"), SizeChartUnit.CM, SizeChartUnit.INCH)).isEqualByComparingTo("1.00");
        assertThat(SizeChartUnits.toDisplay(new BigDecimal("1.00"), SizeChartUnit.CM, SizeChartUnit.INCH)).isEqualByComparingTo("0.39");
        assertThat(SizeChartUnits.toDisplay(new BigDecimal("0.32"), SizeChartUnit.CM, SizeChartUnit.INCH)).isEqualByComparingTo("0.13");
        assertThat(SizeChartUnits.toDisplay(new BigDecimal("1.00"), SizeChartUnit.CM, SizeChartUnit.INCH).scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("INCH → CM 은 2.54 를 곱하고 소수 둘째 자리에서 HALF_UP 한다")
    void inchesToCentimeters() {
        assertThat(SizeChartUnits.toDisplay(new BigDecimal("1.00"), SizeChartUnit.INCH, SizeChartUnit.CM)).isEqualByComparingTo("2.54");
        assertThat(SizeChartUnits.toDisplay(new BigDecimal("1.01"), SizeChartUnit.INCH, SizeChartUnit.CM)).isEqualByComparingTo("2.57");
        assertThat(SizeChartUnits.toDisplay(new BigDecimal("1.01"), SizeChartUnit.INCH, SizeChartUnit.CM).scale()).isEqualTo(2);
    }
}
