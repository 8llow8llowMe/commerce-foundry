package com.commercefoundry.domainlayer.sizechart.application.command;

import java.util.Set;

/** Explicit review; warning IDs come from the stored candidate, never client warning messages. */
public record SizeChartDraftApplyCommand(
    long draftId, long expectedDraftVersion, ProductSizeChartUpsertCommand correctedChart, Set<String> acknowledgedWarningIds, long actorId
) {
    public SizeChartDraftApplyCommand {
        acknowledgedWarningIds = acknowledgedWarningIds == null ? null
            : java.util.Collections.unmodifiableSet(new java.util.HashSet<>(acknowledgedWarningIds));
    }

    @Override
    public String toString() {
        return "SizeChartDraftApplyCommand[draftId=" + draftId + ", expectedDraftVersion=" + expectedDraftVersion + "]";
    }
}
