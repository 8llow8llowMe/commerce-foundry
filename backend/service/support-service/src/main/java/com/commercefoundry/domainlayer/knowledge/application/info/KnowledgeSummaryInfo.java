package com.commercefoundry.domainlayer.knowledge.application.info;

import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeCategory;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeStatus;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeType;
import java.time.Instant;

public record KnowledgeSummaryInfo(
    long documentId, KnowledgeType type, KnowledgeLocale locale, int latestRevision, Integer publishedRevision,
    Long documentVersion, Long revisionVersion, String title, KnowledgeCategory category, KnowledgeStatus status,
    Instant validFrom, Instant validTo, Long approvedBy, Instant approvedAt, Instant publishedAt
) {
}
