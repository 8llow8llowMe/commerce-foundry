package com.commercefoundry.domainlayer.sizechartgeneration.domain.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Untrusted typed AI output for IMAGE input. Same shape as {@link SizeChartGenerationCandidate} plus a per-cell {@code uncertain} flag:
 * the model marks values it could not read clearly. Unreadable values must be null, never guessed. The server turns uncertain
 * non-null values into {@code UNCERTAIN_CELL} warnings that the operator must acknowledge.
 */
public record SizeChartImageGenerationCandidate(String unit, List<Row> rows) {
    public record Row(String sizeLabel, List<Cell> cells) {
        @Override
        public String toString() {
            return "Row[redacted]";
        }
    }

    public record Cell(long columnId, BigDecimal value, Boolean uncertain) {
    }

    @Override
    public String toString() {
        return "SizeChartImageGenerationCandidate[redacted]";
    }
}
