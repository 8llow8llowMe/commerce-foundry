package com.commercefoundry.domainlayer.knowledge.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class KnowledgeRevisionTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final KnowledgeRevision.Content CONTENT = new KnowledgeRevision.Content("배송", "배송 안내", KnowledgeCategory.SHIPPING, NOW, NOW.plusSeconds(60));

    @Test
    void approvalFreezesContentAndPublicationRetainsApproval() {
        KnowledgeRevision draft = KnowledgeRevision.draft(2, 1, 1, CONTENT);
        assertThat(draft.edit(CONTENT).status()).isEqualTo(KnowledgeStatus.DRAFT);
        KnowledgeRevision approved = draft.approve(10, NOW);
        KnowledgeRevision published = approved.publish(NOW);
        assertThat(published.approvedBy()).isEqualTo(10L);
        assertThat(published.approvedAt()).isEqualTo(NOW);
        assertThat(published.archive().content()).isEqualTo(CONTENT);
        assertThatThrownBy(() -> approved.edit(CONTENT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approved.approve(10, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> draft.publish(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> published.archive().publish(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void effectivePeriodIsStartInclusiveEndExclusive() {
        KnowledgeRevision approved = KnowledgeRevision.draft(2, 1, 1, CONTENT).approve(10, NOW);
        assertThatThrownBy(() -> approved.publish(NOW.minusNanos(1))).isInstanceOf(IllegalStateException.class);
        assertThat(approved.publish(NOW).status()).isEqualTo(KnowledgeStatus.PUBLISHED);
        assertThatThrownBy(() -> approved.publish(NOW.plusSeconds(60))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidContentIdsAndPeriod() {
        assertThatThrownBy(() -> new KnowledgeRevision.Content(" ", "body", KnowledgeCategory.OTHER, NOW, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeRevision.Content("x".repeat(201), "body", KnowledgeCategory.OTHER, NOW, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeRevision.Content("title", "x".repeat(8001), KnowledgeCategory.OTHER, NOW, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeRevision.Content("title", "body", KnowledgeCategory.OTHER, NOW, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeRevision.draft(0, 1, 1, CONTENT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeRevision.draft(2, 1, 1, CONTENT).approve(0, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeDocument(1, null, KnowledgeLocale.KO, 1, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void draftPreservesPublishedPointerAndOnlyLatestCanBePublished() {
        KnowledgeDocument current = new KnowledgeDocument(1, KnowledgeType.FAQ, KnowledgeLocale.KO, 1, 1, 0L);
        KnowledgeDocument next = current.nextDraft();
        assertThat(next.latestRevision()).isEqualTo(2);
        assertThat(next.publishedRevision()).isEqualTo(1);
        assertThatThrownBy(() -> next.publish(1)).isInstanceOf(IllegalStateException.class);
        assertThat(next.publish(2).publishedRevision()).isEqualTo(2);
        assertThat(next.unpublish().publishedRevision()).isNull();
    }
}
