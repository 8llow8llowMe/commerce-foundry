package com.commercefoundry.domainlayer.knowledge.application.info;

import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeCategory;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeType;
import java.time.Instant;

public record KnowledgeSearchInfo(
    long documentId, long revisionId, int revisionNumber, String title, String body,
    KnowledgeCategory category, KnowledgeType type, KnowledgeLocale locale, int score, Instant validFrom, Instant validTo
) {
    @Override
    public String toString() {
        return "KnowledgeSearchInfo[documentId=" + documentId + ", revisionId=" + revisionId + ", title=<redacted>, body=<redacted>, score=" + score + "]";
    }
}
