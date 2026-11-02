package com.commercefoundry.domainlayer.knowledge.domain.model;

import java.util.Objects;

/** 지식 게시 정책의 불변식과 버전 계약을 적용한다. */
public record KnowledgeDocument(long id, KnowledgeType type, KnowledgeLocale locale, int latestRevision, Integer publishedRevision, Long version) {

    public KnowledgeDocument {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(locale, "locale");
        if (id <= 0 || latestRevision < 1 || (publishedRevision != null && (publishedRevision < 1 || publishedRevision > latestRevision))) {
            throw new IllegalArgumentException("지식 입력 또는 상태가 올바르지 않습니다.");
        }
    }

    public KnowledgeDocument nextDraft() {
        return new KnowledgeDocument(id, type, locale, Math.incrementExact(latestRevision), publishedRevision, version);
    }

    public KnowledgeDocument publish(int revisionNumber) {
        if (revisionNumber != latestRevision) {
            throw new IllegalStateException("지식 입력 또는 상태가 올바르지 않습니다.");
        }
        return new KnowledgeDocument(id, type, locale, latestRevision, revisionNumber, version);
    }

    public KnowledgeDocument unpublish() {
        return new KnowledgeDocument(id, type, locale, latestRevision, null, version);
    }
}
