package com.commercefoundry.domainlayer.knowledge.domain.model;

import java.time.Instant;
import java.util.Objects;

/** 지식 게시 정책의 불변식과 버전 계약을 적용한다. */
public record KnowledgeRevision(
    long id, long documentId, int revisionNumber, Content content, KnowledgeStatus status,
    Long approvedBy, Instant approvedAt, Instant publishedAt, Long version
) {

    public KnowledgeRevision {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(status, "status");
        if (id <= 0 || documentId <= 0 || revisionNumber < 1 || (approvedBy != null && approvedBy <= 0)) {
            throw new IllegalArgumentException("지식 입력 또는 상태가 올바르지 않습니다.");
        }
        if ((approvedBy == null) != (approvedAt == null) || (status == KnowledgeStatus.DRAFT && approvedBy != null)
            || ((status == KnowledgeStatus.APPROVED || status == KnowledgeStatus.PUBLISHED) && approvedBy == null)
            || (status == KnowledgeStatus.PUBLISHED && publishedAt == null)
            || ((status == KnowledgeStatus.DRAFT || status == KnowledgeStatus.APPROVED) && publishedAt != null)
            || (publishedAt != null && approvedAt == null)) {
            throw new IllegalArgumentException("지식 입력 또는 상태가 올바르지 않습니다.");
        }
    }

    public static KnowledgeRevision draft(long id, long documentId, int revisionNumber, Content content) {
        return new KnowledgeRevision(id, documentId, revisionNumber, content, KnowledgeStatus.DRAFT, null, null, null, null);
    }

    public KnowledgeRevision edit(Content replacement) {
        requireStatus(KnowledgeStatus.DRAFT);
        return new KnowledgeRevision(id, documentId, revisionNumber, replacement, status, approvedBy, approvedAt, publishedAt, version);
    }

    public KnowledgeRevision approve(long actorId, Instant now) {
        requireStatus(KnowledgeStatus.DRAFT);
        return new KnowledgeRevision(id, documentId, revisionNumber, content, KnowledgeStatus.APPROVED, actorId, Objects.requireNonNull(now), null, version);
    }

    public KnowledgeRevision publish(Instant now) {
        requireStatus(KnowledgeStatus.APPROVED);
        if (!content.effectiveAt(now)) {
            throw new IllegalStateException("지식 입력 또는 상태가 올바르지 않습니다.");
        }
        return new KnowledgeRevision(id, documentId, revisionNumber, content, KnowledgeStatus.PUBLISHED, approvedBy, approvedAt, now, version);
    }

    public KnowledgeRevision archive() {
        if (status == KnowledgeStatus.ARCHIVED) {
            return this;
        }
        return new KnowledgeRevision(id, documentId, revisionNumber, content, KnowledgeStatus.ARCHIVED, approvedBy, approvedAt, publishedAt, version);
    }

    private void requireStatus(KnowledgeStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("지식 입력 또는 상태가 올바르지 않습니다.");
        }
    }

    /** 승인 대상의 내용 스냅숏. */
    public record Content(String title, String body, KnowledgeCategory category, Instant validFrom, Instant validTo) {

        public Content {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(validFrom, "validFrom");
            if (title == null || title.isBlank() || title.length() > 200 || body == null || body.isBlank() || body.length() > 8000
                || (validTo != null && !validTo.isAfter(validFrom))) {
                throw new IllegalArgumentException("지식 입력 또는 상태가 올바르지 않습니다.");
            }
        }

        @Override
        public String toString() {
            return "Content[category=" + category + ", validFrom=" + validFrom + ", validTo=" + validTo + "]";
        }

        public boolean effectiveAt(Instant now) {
            return !now.isBefore(validFrom) && (validTo == null || now.isBefore(validTo));
        }
    }
}
