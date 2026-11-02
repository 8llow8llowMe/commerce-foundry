package com.commercefoundry.domainlayer.knowledge.application.port.out.query;

import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeCategory;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeStatus;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeType;
import java.time.Instant;

/** 목록에는 본문을 읽지 않는다. */
public record KnowledgeSummaryQueryResult(
    long documentId, KnowledgeType type, KnowledgeLocale locale, int latestRevision, Integer publishedRevision,
    Long documentVersion, Long revisionVersion, String title, KnowledgeCategory category, KnowledgeStatus status,
    Instant validFrom, Instant validTo, Long approvedBy, Instant approvedAt, Instant publishedAt
) {
}
