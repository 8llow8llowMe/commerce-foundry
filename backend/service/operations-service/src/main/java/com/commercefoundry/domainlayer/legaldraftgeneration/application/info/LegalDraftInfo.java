package com.commercefoundry.domainlayer.legaldraftgeneration.application.info;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import java.time.Instant;
import java.util.List;

/** {@code status} 는 작업 엔진 상태를 작업 어드민 응답과 같은 metadata 로 옮겨 담은 자기 값이다. */
public record LegalDraftInfo(long jobId, CodeNameDescriptionMetadata status, String notice, Candidate candidate, Instant reviewedAt) {
    public record Candidate(CodeNameDescriptionMetadata type, String locale, String title, String body, String templateId, int templateVersion,
                            List<String> unresolvedItems, List<String> reviewChecklist) {
        @Override
        public String toString() {
            return "Candidate[templateId=" + templateId + ", templateVersion=" + templateVersion + "]";
        }
    }
}
