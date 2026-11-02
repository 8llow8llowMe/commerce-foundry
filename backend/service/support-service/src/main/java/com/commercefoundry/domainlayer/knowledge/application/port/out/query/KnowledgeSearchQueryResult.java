package com.commercefoundry.domainlayer.knowledge.application.port.out.query;

import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeCategory;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeType;
import java.time.Instant;

public record KnowledgeSearchQueryResult(
    long documentId, long revisionId, int revisionNumber, String title, String body,
    KnowledgeCategory category, KnowledgeType type, KnowledgeLocale locale, int score, Instant validFrom, Instant validTo
) {
    @Override
    public String toString() {
        return "KnowledgeSearchQueryResult[documentId=" + documentId + ", revisionId=" + revisionId + ", title=<redacted>, body=<redacted>, score=" + score + "]";
    }
}
