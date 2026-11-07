package com.commercefoundry.domainlayer.sizechartgeneration.domain.model;

import java.math.BigDecimal;
import java.util.List;

/** Untrusted typed AI output. Missing measurements are represented by null, never inferred. */
public record SizeChartGenerationCandidate(String unit, List<Row> rows) {
    public record Row(String sizeLabel, List<Cell> cells) {
        @Override
        public String toString() {
            return "Row[redacted]";
        }
    }

    public record Cell(long columnId, BigDecimal value) {
    }

    @Override
    public String toString() {
        return "SizeChartGenerationCandidate[redacted]";
    }
}
