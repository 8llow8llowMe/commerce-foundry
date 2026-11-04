package com.commercefoundry.domainlayer.legaldocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocument;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentKey;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentRevision;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentRuleException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LegalDocumentDomainTest {
    private final LegalDocumentKey key = new LegalDocumentKey(LegalDocumentKey.Type.TERMS, "ko");

    @Test
    void appendRequiresExactVersionAndKeepsPublishedPointer() {
        var root = new LegalDocument(1, key, 1, 1, 3L);
        assertThat(root.append(3L)).isEqualTo(new LegalDocument(1, key, 2, 1, 3L));
        conflict(() -> root.append(null));
        conflict(() -> root.append(2L));
        var first = new LegalDocument(1, key, 0, null, null);
        assertThat(first.append(null).latestRevision()).isEqualTo(1);
        conflict(() -> first.append(0L));
    }

    @Test
    void publishOnlyLatestAndCurrentRetryIsIdempotentEvenWithNewDraft() {
        var root = new LegalDocument(1, key, 3, 1, 4L);
        assertThat(root.publish(0L, 1)).isSameAs(root);
        conflict(() -> root.publish(4L, 2));
        conflict(() -> root.publish(3L, 3));
        assertThat(root.publish(4L, 3).publishedRevision()).isEqualTo(3);
    }

    @Test
    void publicationRequiresReviewAndResolvedPlaceholdersAndPreservesFirstMetadata() {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        var draft = new LegalDocumentRevision(1, 2, 1, "Title", "Body", 7, now, null, null);
        rule(() -> draft.publish(false, 8, now), LegalDocumentRuleException.Reason.REVIEW_REQUIRED);
        for (String marker : new String[]{"{{", "}}"}) {
            var unresolved = new LegalDocumentRevision(1, 2, 1, "Title", "Body " + marker, 7, now, null, null);
            rule(() -> unresolved.publish(true, 8, now), LegalDocumentRuleException.Reason.UNRESOLVED_PLACEHOLDER);
        }
        var published = draft.publish(true, 8, now.plusSeconds(1));
        assertThat(published.publish(true, 9, now.plusSeconds(2))).isSameAs(published);
        assertThat(draft.publishedAt()).isNull();
        assertThat(published.title()).isEqualTo(draft.title());
        assertThat(published.body()).isEqualTo(draft.body());
    }

    private void conflict(Runnable action) {
        rule(action, LegalDocumentRuleException.Reason.CONFLICT);
    }

    private void rule(Runnable action, LegalDocumentRuleException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LegalDocumentRuleException.class,
            exception -> assertThat(exception.reason()).isEqualTo(reason));
    }
}
