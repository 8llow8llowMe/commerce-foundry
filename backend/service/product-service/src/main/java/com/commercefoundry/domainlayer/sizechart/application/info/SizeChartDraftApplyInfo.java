package com.commercefoundry.domainlayer.sizechart.application.info;

import java.time.Instant;

/** Immutable receipt: identifiers and application metadata only, no source, labels or notes. */
public record SizeChartDraftApplyInfo(long draftId, long productId, long chartId, long chartRevision, long appliedByMemberId, Instant appliedAt) {
}
