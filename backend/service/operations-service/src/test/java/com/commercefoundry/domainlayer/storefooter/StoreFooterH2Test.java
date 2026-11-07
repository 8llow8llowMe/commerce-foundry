package com.commercefoundry.domainlayer.storefooter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.storefooter.adapter.out.config.StoreFooterConfig;
import com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.StoreFooterRepositoryAdapter;
import com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.entity.StoreFooterEntity;
import com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.repository.StoreFooterLinkRepository;
import com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.repository.StoreFooterRepository;
import com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.repository.StoreFooterRevisionRepository;
import com.commercefoundry.domainlayer.storefooter.application.command.StoreFooterCommands;
import com.commercefoundry.domainlayer.storefooter.application.exception.StoreFooterErrorCode;
import com.commercefoundry.domainlayer.storefooter.application.exception.StoreFooterException;
import com.commercefoundry.domainlayer.storefooter.application.service.processor.StoreFooterProcessor;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterContent;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterContent.Link;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterContent.Text;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterUrlPolicy;
import com.commercefoundry.global.config.OperationsServiceFeaturesConfig;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@EntityScan(basePackageClasses = StoreFooterEntity.class)
@EnableJpaRepositories(basePackageClasses = StoreFooterRepository.class)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:store-footer-persistence-597;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:ddl/operations-schema.sql", "spring.jpa.defer-datasource-initialization=false",
    "store.footer.allowed-external-hosts=links.example.com"
})
@Import({OperationsServiceFeaturesConfig.class, StoreFooterConfig.class, StoreFooterRepositoryAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StoreFooterH2Test {
    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    private final AtomicLong ids = new AtomicLong(1000);
    @Autowired StoreFooterRepositoryAdapter adapter;
    @Autowired StoreFooterRepository footers;
    @Autowired StoreFooterRevisionRepository revisions;
    @Autowired StoreFooterLinkRepository links;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void clear() {
        links.deleteAll();
        revisions.deleteAll();
        footers.deleteAll();
    }

    @Test
    void snapshotsKeepBilingualHiddenLinksAndDescendingHistoryAcrossFreshTransactions() {
        var first = draft(null, content("Original"));
        assertThat(first.footer().version()).isZero();
        assertThat(adapter.published()).isEmpty();
        assertThat(adapter.detail().orElseThrow().revision().content().links()).extracting(Link::displayOrder).containsExactly(0, 10);
        var published = adapter.publish(0L, 1, 8, NOW.plusSeconds(1));
        var original = adapter.published().orElseThrow();
        assertThat(original.publishedBy()).isEqualTo(8L);
        assertThat(original.publishedAt()).isEqualTo(NOW.plusSeconds(1));
        var latest = draft(published.footer().version(), content("Private"));
        assertThat(latest.footer().latestRevision()).isEqualTo(2);
        assertThat(latest.revision().publishedAt()).isNull();
        assertThat(adapter.published().orElseThrow()).isEqualTo(original);
        var beforeRetry = adapter.detail().orElseThrow();
        assertThat(adapter.publish(0L, 1, 9, NOW.plusSeconds(9))).isEqualTo(beforeRetry);
        assertThat(adapter.detail().orElseThrow()).isEqualTo(beforeRetry);
        assertThat(adapter.published().orElseThrow()).isEqualTo(original);
        var history = adapter.revisions(PageRequest.of(0, 1, Sort.by("revisionNumber").descending()));
        assertThat(history.getTotalElements()).isEqualTo(2);
        assertThat(history.getContent()).containsExactly(adapter.detail().orElseThrow().revision());
        var older = adapter.revisions(PageRequest.of(1, 1, Sort.by("revisionNumber").descending())).getContent();
        assertThat(older).containsExactly(original);
        assertThat(original.content().copyrightText()).isEqualTo(new Text("국문 Original", "English Original"));
        assertThat(original.content().links()).extracting(Link::visible).containsExactly(false, true);
        assertThat(original.content().links().get(0).labels()).isEqualTo(new Text("숨김 Original", "Hidden Original"));

        // Restoring copies an old snapshot into a new revision; the historical author/publication remain unchanged.
        var restored = draft(latest.footer().version(), original.content());
        assertThat(restored.revision().revisionNumber()).isEqualTo(3);
        adapter.publish(restored.footer().version(), 3, 10, NOW.plusSeconds(10));
        assertThat(adapter.published().orElseThrow().content()).isEqualTo(original.content());
        assertThat(adapter.revisions(PageRequest.of(0, 10, Sort.by("revisionNumber").descending())).getContent())
            .extracting(revision -> revision.revisionNumber()).containsExactly(3, 2, 1);
        assertThat(adapter.revisions(PageRequest.of(2, 1, Sort.by("revisionNumber").descending())).getContent()).containsExactly(original);
    }

    @Test
    void identicalSaveCreatesNewRevisionAndEmptyReplacementRetainsPreviousSnapshot() {
        var first = draft(null, content("Example"));
        var second = draft(0L, first.revision().content());
        assertThat(second.footer().version()).isEqualTo(1L);
        assertThat(second.revision().revisionNumber()).isEqualTo(2);
        assertThat(second.revision().id()).isNotEqualTo(first.revision().id());
        var empty = new StoreFooterContent(new Text(null, null), List.of());
        var third = draft(1L, empty);
        adapter.publish(third.footer().version(), 3, 8, NOW);
        assertThat(adapter.published().orElseThrow().content()).isEqualTo(empty);
        assertThat(links.count()).isEqualTo(4);
        assertThat(adapter.revisions(PageRequest.of(1, 1, Sort.by("revisionNumber").descending())).getContent())
            .singleElement().satisfies(row -> assertThat(row.content().links()).hasSize(2));
    }

    @Test
    void failedLinkInsertRollsBackNewRootRevisionAndEarlierLinkInsert() {
        // Duplicate assigned link PK fails after the root/revision and first link have been persisted.
        assertThatThrownBy(() -> adapter.saveDraft(null, content("Example"), 2000, List.of(2001L, 2001L), 7, NOW))
            .isInstanceOf(RuntimeException.class).isNotInstanceOf(StoreFooterException.class);
        assertThat(adapter.detail()).isEmpty();
        assertThat(footers.count()).isZero();
        assertThat(revisions.count()).isZero();
        assertThat(links.count()).isZero();
    }

    @Test
    void independentCommitSurvivesOuterRollbackAndConflictDoesNotPoisonCallerTransaction() {
        var outer = new TransactionTemplate(transactions);
        outer.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            var saved = processorAt(NOW).save(7, new StoreFooterCommands.SaveDraft(null, content("Independent")));
            assertThat(saved.footer().version()).isZero();
            assertThat(status.isRollbackOnly()).isFalse();
            status.setRollbackOnly();
        });
        var committed = adapter.detail().orElseThrow();
        assertThat(committed.revision().content().copyrightText().en()).isEqualTo("English Independent");

        outer.executeWithoutResult(status -> {
            error(() -> processorAt(NOW).save(7, new StoreFooterCommands.SaveDraft(null, content("Rejected"))),
                StoreFooterErrorCode.CONFLICT);
            assertThat(status.isRollbackOnly()).isFalse();
        });
        assertThat(adapter.detail()).contains(committed);
        assertThat(revisions.count()).isEqualTo(1);
    }

    @Test
    void failedLinkInsertRollsBackUpdatedPointerAndPreservesPublishedSnapshot() {
        draft(null, content("Original"));
        adapter.publish(0L, 1, 8, NOW);
        var before = adapter.detail().orElseThrow();
        var publicBefore = adapter.published().orElseThrow();
        assertThatThrownBy(() -> adapter.saveDraft(before.footer().version(), content("Replacement"), 3000,
            List.of(3001L, 3001L), 9, NOW.plusSeconds(1))).isInstanceOf(RuntimeException.class)
            .isNotInstanceOf(StoreFooterException.class);
        assertThat(adapter.detail().orElseThrow()).isEqualTo(before);
        assertThat(adapter.published().orElseThrow()).isEqualTo(publicBefore);
        assertThat(revisions.count()).isEqualTo(1);
        assertThat(links.count()).isEqualTo(2);
    }

    @Test
    void firstPublicationRevalidatesStoredDraftAgainstCurrentDeploymentAllowlist() {
        // Port fixture simulates a previously accepted host removed from deployment configuration.
        var removed = new StoreFooterContent(new Text(null, null), List.of(new Link("CUSTOM", new Text("예시", "Example"),
            "https://removed.example.com/help", 0, false)));
        draft(null, removed);
        var before = adapter.detail().orElseThrow();
        error(() -> processorAt(NOW).publish(8, new StoreFooterCommands.Publish(0L, 1)), StoreFooterErrorCode.URL);
        assertThat(adapter.detail().orElseThrow()).isEqualTo(before);
        assertThat(adapter.published()).isEmpty();
        assertThat(revisions.count()).isEqualTo(1);
    }

    @Test
    void staleVersionAndNonLatestRevisionNeverPartiallyChangePublication() {
        var processor = processorAt(NOW);
        error(() -> processor.save(7, new StoreFooterCommands.SaveDraft(0L, content("Example"))), StoreFooterErrorCode.CONFLICT);
        processor.save(7, new StoreFooterCommands.SaveDraft(null, content("Example")));
        error(() -> processor.save(7, new StoreFooterCommands.SaveDraft(null, content("Duplicate"))), StoreFooterErrorCode.CONFLICT);
        processor.save(7, new StoreFooterCommands.SaveDraft(0L, content("Latest")));
        var before = adapter.detail().orElseThrow();
        error(() -> processor.publish(8, new StoreFooterCommands.Publish(0L, 2)), StoreFooterErrorCode.CONFLICT);
        error(() -> processor.publish(8, new StoreFooterCommands.Publish(1L, 1)), StoreFooterErrorCode.CONFLICT);
        assertThat(adapter.detail().orElseThrow()).isEqualTo(before);
        assertThat(adapter.published()).isEmpty();
        assertThat(revisions.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-11-06T00:00:00.123456789Z, 2026-11-06T00:00:00.123456Z",
        "2026-11-06T00:00:00.999999999Z, 2026-11-06T00:00:00.999999Z",
        "2026-11-06T00:00:01.000000001Z, 2026-11-06T00:00:01Z"
    })
    void processorResponseMatchesCommittedMicrosecondPrecision(String input, String expected) {
        var processor = processorAt(Instant.parse(input));
        var unordered = content("Example");
        var ordered = new StoreFooterContent(unordered.copyrightText(), unordered.links().reversed());
        var saved = processor.save(7, new StoreFooterCommands.SaveDraft(null, ordered));
        assertThat(saved.revision().createdAt()).isEqualTo(Instant.parse(expected));
        assertThat(processor.detail()).isEqualTo(saved);
        var published = processor.publish(8, new StoreFooterCommands.Publish(saved.footer().version(), 1));
        assertThat(published.revision().publishedAt()).isEqualTo(Instant.parse(expected));
        assertThat(processor.detail()).isEqualTo(published);
        assertThat(processorAt(Instant.parse(input).plusSeconds(10)).publish(9, new StoreFooterCommands.Publish(0L, 1))).isEqualTo(published);
    }

    private com.commercefoundry.domainlayer.storefooter.application.port.out.query.StoreFooterQueryResults.Detail draft(
        Long version, StoreFooterContent content
    ) {
        return adapter.saveDraft(version, content, ids.incrementAndGet(), content.links().stream().map(link -> ids.incrementAndGet()).toList(), 7, NOW);
    }

    private StoreFooterContent content(String text) {
        // Input is deliberately unordered; repository reads must order both visible and hidden snapshot rows.
        return new StoreFooterContent(new Text("국문 " + text, "English " + text), List.of(
            new Link("CUSTOM", new Text("공개 " + text, "Visible " + text), "https://links.example.com/visible", 10, true),
            new Link("CUSTOM", new Text("숨김 " + text, "Hidden " + text), "https://links.example.com/hidden", 0, false)));
    }

    private StoreFooterProcessor processorAt(Instant now) {
        return new StoreFooterProcessor(adapter, new StoreFooterUrlPolicy(Set.of("links.example.com")),
            Clock.fixed(now, ZoneOffset.UTC), new SnowflakeIdGenerator(0, 0));
    }

    private void error(Runnable action, StoreFooterErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(StoreFooterException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
