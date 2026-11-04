package com.commercefoundry.domainlayer.legaldocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.LegalDocumentRepositoryAdapter;
import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.repository.LegalDocumentRepository;
import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.repository.LegalDocumentRevisionRepository;
import com.commercefoundry.domainlayer.legaldocument.application.command.LegalDocumentCommands;
import com.commercefoundry.domainlayer.legaldocument.application.exception.LegalDocumentErrorCode;
import com.commercefoundry.domainlayer.legaldocument.application.exception.LegalDocumentException;
import com.commercefoundry.domainlayer.legaldocument.application.service.processor.LegalDocumentProcessor;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentKey;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

abstract class LegalDocumentPersistenceContract {
    static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    static final LegalDocumentKey KEY = new LegalDocumentKey(LegalDocumentKey.Type.TERMS, "ko");
    final AtomicLong ids = new AtomicLong(1000);
    @Autowired
    LegalDocumentRepositoryAdapter adapter;
    @Autowired
    LegalDocumentRepository documents;
    @Autowired
    LegalDocumentRevisionRepository revisions;

    @BeforeEach
    void clear() {
        revisions.deleteAll();
        documents.deleteAll();
    }

    @ParameterizedTest
    @CsvSource({
        "2026-11-04T00:00:00.123456789Z, 2026-11-04T00:00:00.123456Z",
        "2026-11-04T00:00:00.999999999Z, 2026-11-04T00:00:00.999999Z",
        "2026-11-04T00:00:01.000000001Z, 2026-11-04T00:00:01Z"
    })
    void processorResponsesMatchCommittedReadsAtMicrosecondFloor(String input, String expected) {
        Instant now = Instant.parse(input);
        Instant floor = Instant.parse(expected);
        var processor = processorAt(now);
        var saved = processor.save(7, new LegalDocumentCommands.SaveDraft("TERMS", "ko", null, "Title", "Body"));
        // Both concrete suites suspend the test transaction: every adapter call opens a fresh transaction.
        var savedRead = processor.detail("TERMS", "ko");
        assertThat(saved.revision().createdAt()).isEqualTo(floor);
        assertThat(savedRead).isEqualTo(saved);

        var command = new LegalDocumentCommands.Publish("TERMS", "ko", saved.document().version(), 1, true);
        var published = processor.publish(8, command);
        var publishedRead = processor.detail("TERMS", "ko");
        assertThat(published.revision().publishedAt()).isEqualTo(floor);
        assertThat(published.revision().publishedAt().getEpochSecond()).isEqualTo(now.getEpochSecond());
        assertThat(publishedRead).isEqualTo(published);
        assertThat(processor.published("TERMS", "ko", null).publishedAt()).isEqualTo(floor);
        assertThat(processor.published("ko")).singleElement().satisfies(row -> assertThat(row.publishedAt()).isEqualTo(floor));
        assertThat(processor.published("TERMS", "ko", 1).publishedAt()).isEqualTo(floor);
        assertThat(processor.revisions("TERMS", "ko", 0, 10).contents()).containsExactly(published.revision());
        assertThat(processorAt(now.plusSeconds(10)).publish(9, command)).isEqualTo(published);
        assertThat(processor.detail("TERMS", "ko")).isEqualTo(published);
    }

    @Test
    void processorKeepsHistoricalTimestampsAndRetryReturnsLatestDraft() {
        Instant createdAt = Instant.parse("2026-10-01T00:00:00.654321Z");
        Instant publishedAt = Instant.parse("2026-10-02T00:00:00.987654Z");
        var original = adapter.saveDraft(KEY, null, "Old title", "Old body", 7, ids.incrementAndGet(), ids.incrementAndGet(), createdAt);
        adapter.publish(KEY, 0L, 1, true, 8, publishedAt);
        var before = revisions.findByDocumentIdAndRevisionNumber(original.document().id(), 1).orElseThrow().toDomain();
        var processor = processorAt(Instant.parse("2026-11-04T00:00:00.999999999Z"));

        var retry = processor.publish(9, new LegalDocumentCommands.Publish("TERMS", "ko", 0L, 1, true));
        assertThat(retry.revision()).isEqualTo(before);
        var latest = processor.save(10, new LegalDocumentCommands.SaveDraft("TERMS", "ko", retry.document().version(), "New title", "New body"));
        assertThat(latest.revision().createdAt()).isEqualTo(Instant.parse("2026-11-04T00:00:00.999999Z"));
        assertThat(processor.publish(11, new LegalDocumentCommands.Publish("TERMS", "ko", 0L, 1, true))).isEqualTo(latest);
        assertThat(processor.detail("TERMS", "ko")).isEqualTo(latest);

        var historical = revisions.findByDocumentIdAndRevisionNumber(original.document().id(), 1).orElseThrow().toDomain();
        assertThat(historical).isEqualTo(before);
        assertThat(historical.createdAt()).isEqualTo(createdAt);
        assertThat(historical.publishedAt()).isEqualTo(publishedAt);
        assertThat(processor.published("TERMS", "ko", 1).publishedAt()).isEqualTo(publishedAt);
    }

    private LegalDocumentProcessor processorAt(Instant now) {
        return new LegalDocumentProcessor(adapter, new SnowflakeIdGenerator(0, 0), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void draftsPublicationRollbackHistoryAndImmutableOldContent() {
        var first = draft(KEY, null, "First body");
        assertThat(first.document().version()).isZero();
        assertThat(adapter.published("ko")).isEmpty();
        assertThat(adapter.published(KEY, 1)).isEmpty();
        var published = adapter.publish(KEY, 0L, 1, true, 8, NOW.plusSeconds(1));
        assertThat(published.document().version()).isEqualTo(1L);
        var second = draft(KEY, 1L, "Unpublished body");
        assertThat(second.document().latestRevision()).isEqualTo(2);
        assertThat(adapter.published(KEY, null).orElseThrow().body()).isEqualTo("First body");
        assertThat(adapter.published(KEY, 2)).isEmpty();
        var retry = adapter.publish(KEY, 0L, 1, true, 9, NOW.plusSeconds(4));
        assertThat(retry.document().version()).isEqualTo(2L);
        assertThat(retry).isEqualTo(adapter.detail(KEY).orElseThrow());
        assertThat(retry.revision().revisionNumber()).isEqualTo(2);
        assertThat(retry.revision().body()).isEqualTo("Unpublished body");
        assertThat(retry.revision().publishedBy()).isNull();
        assertThat(retry.revision().publishedAt()).isNull();
        var originallyPublished = revisions.findByDocumentIdAndRevisionNumber(first.document().id(), 1).orElseThrow().toDomain();
        assertThat(originallyPublished.publishedBy()).isEqualTo(8);
        assertThat(originallyPublished.publishedAt()).isEqualTo(NOW.plusSeconds(1));
        error(() -> adapter.publish(KEY, 1L, 2, true, 9, NOW), LegalDocumentErrorCode.CONFLICT);
        var restored = draft(KEY, 2L, "First body");
        assertThat(restored.revision().revisionNumber()).isEqualTo(3);
        error(() -> adapter.publish(KEY, 3L, 2, true, 9, NOW), LegalDocumentErrorCode.CONFLICT);
        adapter.publish(KEY, 3L, 3, true, 9, NOW.plusSeconds(5));
        assertThat(adapter.published(KEY, null).orElseThrow().revisionNumber()).isEqualTo(3);
        assertThat(adapter.published(KEY, 1).orElseThrow().body()).isEqualTo("First body");
        assertThat(adapter.published(KEY, 2)).isEmpty();
        var page = adapter.revisions(KEY, PageRequest.of(0, 1, Sort.by("revisionNumber").descending()));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).singleElement().satisfies(revision -> assertThat(revision.revisionNumber()).isEqualTo(3));
        var old = revisions.findByDocumentIdAndRevisionNumber(first.document().id(), 1).orElseThrow().toDomain();
        assertThat(old.title()).isEqualTo("Example title");
        assertThat(old.createdBy()).isEqualTo(7);
        assertThat(old.publishedBy()).isEqualTo(8);
        assertThat(old.publishedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void failedRevisionInsertRollsBackRootAndPreservesPublishedContent() {
        var first = draft(KEY, null, "First body");
        adapter.publish(KEY, 0L, 1, true, 8, NOW);
        var before = adapter.detail(KEY).orElseThrow();
        assertThatThrownBy(() -> adapter.saveDraft(KEY, 1L, "Replacement", "Replacement body", 9,
            ids.incrementAndGet(), first.revision().id(), NOW.plusSeconds(1))).isInstanceOf(RuntimeException.class);
        assertThat(adapter.detail(KEY).orElseThrow()).isEqualTo(before);
        assertThat(revisions.count()).isEqualTo(1);
        assertThat(adapter.published(KEY, null).orElseThrow().body()).isEqualTo("First body");
    }

    @Test
    void guardsVersionReviewAndUnresolvedMarkersWithoutPartialPublication() {
        error(() -> draft(KEY, 0L, "Body"), LegalDocumentErrorCode.CONFLICT);
        draft(KEY, null, "Body {{COMPANY_NAME}}");
        error(() -> draft(KEY, null, "Body"), LegalDocumentErrorCode.CONFLICT);
        error(() -> adapter.publish(KEY, 0L, 1, false, 8, NOW), LegalDocumentErrorCode.REVIEW_REQUIRED);
        error(() -> adapter.publish(KEY, 0L, 1, true, 8, NOW), LegalDocumentErrorCode.UNRESOLVED_PLACEHOLDER);
        assertThat(adapter.detail(KEY).orElseThrow().document().version()).isZero();
        assertThat(adapter.published("ko")).isEmpty();
        assertThat(revisions.count()).isEqualTo(1);
    }

    @Test
    void publicJoinSeparatesTypesLocalesAndPreviouslyPublishedFromDrafts() {
        for (var type : LegalDocumentKey.Type.values()) {
            for (String locale : new String[]{"ko", "en"}) {
                var key = new LegalDocumentKey(type, locale);
                draft(key, null, type + " " + locale);
                if (type == LegalDocumentKey.Type.TERMS && locale.equals("ko")) {
                    adapter.publish(key, 0L, 1, true, 8, NOW);
                }
            }
        }
        assertThat(adapter.list()).hasSize(4);
        assertThat(adapter.published("ko")).singleElement().satisfies(row -> {
            assertThat(row.type()).isEqualTo("TERMS");
            assertThat(row.locale()).isEqualTo("ko");
        });
        assertThat(adapter.published("en")).isEmpty();
        assertThat(adapter.published(new LegalDocumentKey(LegalDocumentKey.Type.TERMS, "en"), null)).isEmpty();
        assertThat(adapter.published(new LegalDocumentKey(LegalDocumentKey.Type.PRIVACY, "ko"), 1)).isEmpty();
    }

    com.commercefoundry.domainlayer.legaldocument.application.port.out.query.LegalDocumentQueryResults.Detail draft(
        LegalDocumentKey key, Long version, String body
    ) {
        return adapter.saveDraft(key, version, "Example title", body, 7, ids.incrementAndGet(), ids.incrementAndGet(), NOW);
    }

    static void error(Runnable action, LegalDocumentErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LegalDocumentException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
