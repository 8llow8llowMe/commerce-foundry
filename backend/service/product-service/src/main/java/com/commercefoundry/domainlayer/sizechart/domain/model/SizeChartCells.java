package com.commercefoundry.domainlayer.sizechart.domain.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 사이즈 행 하나가 템플릿 항목을 빠짐없이 한 번씩 가리키는지. 저장소 없이 판정한다.
 */
public final class SizeChartCells {

    private SizeChartCells() {
    }

    public static SizeChartCellViolation violation(Set<Long> templateColumnIds, List<Long> cellColumnIds) {
        Set<Long> seen = new HashSet<>();
        for (Long columnId : cellColumnIds) {
            if (!seen.add(columnId)) {
                return SizeChartCellViolation.DUPLICATED;
            }
        }
        for (Long columnId : seen) {
            if (!templateColumnIds.contains(columnId)) {
                return SizeChartCellViolation.UNKNOWN;
            }
        }
        if (seen.size() != templateColumnIds.size()) {
            return SizeChartCellViolation.MISSING;
        }
        return SizeChartCellViolation.NONE;
    }
}
