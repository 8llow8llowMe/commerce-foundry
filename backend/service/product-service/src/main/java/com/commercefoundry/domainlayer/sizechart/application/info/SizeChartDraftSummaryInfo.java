package com.commercefoundry.domainlayer.sizechart.application.info;

import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import java.time.Instant;

public record SizeChartDraftSummaryInfo(
    long draftId, long productId, long templateId, long requestedByMemberId, Long jobId,
    SizeChartDraftStatus status, long version, Instant expiresAt
) {
    public static SizeChartDraftSummaryInfo from(SizeChartDraft draft) {
        return new SizeChartDraftSummaryInfo(draft.id(), draft.input().productId(), draft.input().templateId(),
            draft.input().requestedByMemberId(), draft.jobId(), draft.status(), draft.version(), draft.expiresAt());
    }
}
