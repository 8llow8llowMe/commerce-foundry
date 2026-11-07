package com.commercefoundry.domainlayer.sizechart.application.info;

import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import java.time.Instant;

/** Local view deliberately excludes source text, request hashes and provider diagnostics. */
public record SizeChartDraftInfo(
    long draftId, long productId, long templateId, long templateRevision, String templateJson,
    Long baseChartId, Long baseChartRevision, Long jobId, SizeChartDraftStatus status, long version,
    Instant expiresAt, String candidateJson
) {
    public static SizeChartDraftInfo from(SizeChartDraft draft) {
        var input = draft.input();
        return new SizeChartDraftInfo(draft.id(), input.productId(), input.templateId(), input.templateRevision(), input.templateJson(),
            input.baseChartId(), input.baseChartRevision(), draft.jobId(), draft.status(), draft.version(), draft.expiresAt(),
            draft.candidate() == null ? null : draft.candidate().json());
    }

    @Override
    public String toString() {
        return "SizeChartDraftInfo[draftId=" + draftId + ", status=" + status + ", version=" + version + "]";
    }
}
