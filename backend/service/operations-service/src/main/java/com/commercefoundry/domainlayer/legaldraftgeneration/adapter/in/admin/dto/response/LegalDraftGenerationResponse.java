package com.commercefoundry.domainlayer.legaldraftgeneration.adapter.in.admin.dto.response;

import java.time.Instant;
import java.util.List;

public record LegalDraftGenerationResponse(String jobId, String status, String notice, Candidate candidate, Instant reviewedAt) {
    public record Accepted(String jobId) {}

    public record Candidate(String type, String locale, String title, String body, String templateId, int templateVersion,
                            List<String> unresolvedItems, List<String> reviewChecklist) {
        @Override
        public String toString() {
            return "Candidate[templateId=" + templateId + ", templateVersion=" + templateVersion + "]";
        }
    }
}
