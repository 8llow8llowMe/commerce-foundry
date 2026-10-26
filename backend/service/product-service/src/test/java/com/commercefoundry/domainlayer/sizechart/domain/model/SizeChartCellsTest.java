package com.commercefoundry.domainlayer.sizechart.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 사이즈 행의 칸이 템플릿 항목을 빠짐없이 한 번씩 가리키는지. 중복을 모르는 항목 · 누락보다 먼저 본다. */
class SizeChartCellsTest {

    private static final Set<Long> TEMPLATE = Set.of(1L, 2L);

    @Test
    @DisplayName("템플릿 항목을 한 번씩 모두 담으면 통과한다")
    void complete() {
        assertThat(SizeChartCells.violation(TEMPLATE, List.of(2L, 1L))).isEqualTo(SizeChartCellViolation.NONE);
    }

    @Test
    @DisplayName("같은 항목이 두 번이면 중복이다 — 그 항목이 템플릿에 없어도 중복을 먼저 본다")
    void duplicated() {
        assertThat(SizeChartCells.violation(TEMPLATE, List.of(1L, 2L, 1L))).isEqualTo(SizeChartCellViolation.DUPLICATED);
        assertThat(SizeChartCells.violation(TEMPLATE, List.of(9L, 9L))).isEqualTo(SizeChartCellViolation.DUPLICATED);
    }

    @Test
    @DisplayName("템플릿에 없는 항목이면 모르는 항목이다")
    void unknown() {
        assertThat(SizeChartCells.violation(TEMPLATE, List.of(1L, 9L))).isEqualTo(SizeChartCellViolation.UNKNOWN);
    }

    @Test
    @DisplayName("템플릿 항목이 빠지면 누락이다")
    void missing() {
        assertThat(SizeChartCells.violation(TEMPLATE, List.of(1L))).isEqualTo(SizeChartCellViolation.MISSING);
    }
}
