package com.commercefoundry.domainlayer.legaldraftgeneration.application.info;

import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import java.time.Instant;
import java.util.List;

public record LegalDraftInfo(long jobId, JobStatus status, String notice, Candidate candidate, Instant reviewedAt) {
    public record Candidate(String type, String locale, String title, String body, String templateId, int templateVersion,
                            List<String> unresolvedItems, List<String> reviewChecklist) {
        @Override
        public String toString() {
            return "Candidate[templateId=" + templateId + ", templateVersion=" + templateVersion + "]";
        }
    }
}
