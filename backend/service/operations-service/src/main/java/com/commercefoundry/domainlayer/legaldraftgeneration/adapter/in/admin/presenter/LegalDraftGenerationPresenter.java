package com.commercefoundry.domainlayer.legaldraftgeneration.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.legaldraftgeneration.adapter.in.admin.dto.response.LegalDraftGenerationResponse;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.info.LegalDraftInfo;
import org.springframework.stereotype.Component;

@Component
public class LegalDraftGenerationPresenter {
    public LegalDraftGenerationResponse.Accepted accepted(long jobId) {
        return new LegalDraftGenerationResponse.Accepted(Long.toString(jobId));
    }

    public LegalDraftGenerationResponse toResponse(LegalDraftInfo info) {
        var candidate = info.candidate();
        var body = candidate == null ? null : new LegalDraftGenerationResponse.Candidate(candidate.type(), candidate.locale(),
            candidate.title(), candidate.body(), candidate.templateId(), candidate.templateVersion(), candidate.unresolvedItems(),
            candidate.reviewChecklist());
        return new LegalDraftGenerationResponse(Long.toString(info.jobId()), info.status().name(), info.notice(), body, info.reviewedAt());
    }
}
