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
import com.commercefoundry.domainlayer.storefooter.application.port.out.query.StoreFooterQueryResults;
import com.commercefoundry.domainlayer.storefooter.application.service.processor.StoreFooterProcessor;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterContent;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterContent.Link;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterContent.Text;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterRevision;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterUrlPolicy;
import com.commercefoundry.global.config.OperationsServiceFeaturesConfig;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@EntityScan(basePackageClasses = StoreFooterEntity.class)
@EnableJpaRepositories(basePackageClasses = StoreFooterRepository.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=operations",
    "spring.sql.init.mode=never", "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
    "spring.datasource.hikari.connection-init-sql=SET statement_timeout = '10s'",
    "store.footer.allowed-external-hosts=links.example.com"
})
@Import({OperationsServiceFeaturesConfig.class, StoreFooterConfig.class, StoreFooterRepositoryAdapter.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StoreFooterPostgresTest {
    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00.123456Z");
    private final AtomicLong ids = new AtomicLong(1000);
    private final SnowflakeIdGenerator generator = new SnowflakeIdGenerator(0, 0);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    @Autowired StoreFooterRepositoryAdapter adapter;
    @Autowired StoreFooterRepository footers;
    @Autowired StoreFooterRevisionRepository revisions;
    @Autowired StoreFooterLinkRepository links;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clear() {
        links.deleteAllInBatch();
        revisions.deleteAllInBatch();
        footers.deleteAllInBatch();
    }

    @Test
    void concurrentFirstInsertUsesIndependentTransactionsAndKeepsOnlyWinningSnapshot() throws Exception {
        race(() -> draft(null, content("First A")), () -> draft(null, content("First B")));
        var result = adapter.detail().orElseThrow();
        assertThat(result.footer().version()).isZero();
        assertThat(result.footer().latestRevision()).isEqualTo(1);
        assertThat(result.footer().publishedRevision()).isNull();
        assertThat(result.revision().content().copyrightText().en()).isIn("English First A", "English First B");
        assertThat(adapter.published()).isEmpty();
        counts(1, 2);
        assertThat(history(0, 10).getContent()).containsExactly(result.revision());
    }

    @Test
    void concurrentDraftsCommitOneNewRevisionAndDiscardLosersLinks() throws Exception {
        draft(null, content("Initial"));
        var original = adapter.detail().orElseThrow().revision();
        race(() -> draft(0L, content("Draft A")), () -> draft(0L, content("Draft B")));
        var result = adapter.detail().orElseThrow();
        assertThat(result.footer().version()).isEqualTo(1L);
        assertThat(result.footer().latestRevision()).isEqualTo(2);
        assertThat(result.revision().content().copyrightText().en()).isIn("English Draft A", "English Draft B");
        assertThat(adapter.published()).isEmpty();
        counts(2, 4);
        assertThat(history(0, 10).getContent()).containsExactly(result.revision(), original);
    }

    @Test
    void draftAndPublishRaceCommitExactlyOneCompleteChange() throws Exception {
        draft(null, content("Initial"));
        var original = adapter.detail().orElseThrow().revision();
        var outcomes = race(() -> draft(0L, content("Next")), () -> adapter.publish(0L, 1, 8, NOW.plusSeconds(1)));
        var result = adapter.detail().orElseThrow();
        assertThat(result.footer().version()).isEqualTo(1L);
        if (outcomes.get(0)) {
            assertThat(result.footer().latestRevision()).isEqualTo(2);
            assertThat(result.footer().publishedRevision()).isNull();
            assertThat(adapter.published()).isEmpty();
            counts(2, 4);
            assertThat(history(1, 1).getContent()).containsExactly(original);
            conflict(() -> processorAt(NOW).publish(9, new StoreFooterCommands.Publish(0L, 1)));
        } else {
            assertThat(result.footer().latestRevision()).isEqualTo(1);
            assertThat(result.footer().publishedRevision()).isEqualTo(1);
            assertThat(adapter.published()).contains(result.revision());
            assertThat(result.revision().content()).isEqualTo(original.content());
            assertThat(result.revision().publishedBy()).isEqualTo(8L);
            assertThat(result.revision().publishedAt()).isEqualTo(NOW.plusSeconds(1));
            counts(1, 2);
            assertThat(adapter.publish(0L, 1, 9, NOW.plusSeconds(2))).isEqualTo(result);
        }
        assertThat(adapter.detail()).contains(result);
    }

    @Test
    void concurrentPublicationsHaveOne409AndRetryRetainsFirstPublisherAndVersion() throws Exception {
        draft(null, content("Initial"));
        var outcomes = race(() -> adapter.publish(0L, 1, 8, NOW.plusSeconds(1)),
            () -> adapter.publish(0L, 1, 9, NOW.plusSeconds(2)));
        var before = adapter.detail().orElseThrow();
        assertThat(before.footer().version()).isEqualTo(1L);
        assertThat(before.footer().publishedRevision()).isEqualTo(1);
        assertThat(before.revision().publishedBy()).isEqualTo(outcomes.get(0) ? 8L : 9L);
        assertThat(before.revision().publishedAt()).isEqualTo(NOW.plusSeconds(outcomes.get(0) ? 1 : 2));
        assertThat(adapter.publish(0L, 1, 10, NOW.plusSeconds(3))).isEqualTo(before);
        assertThat(adapter.detail()).contains(before);
        assertThat(adapter.published()).contains(before.revision());
        assertThat(history(0, 10).getContent()).containsExactly(before.revision());
        counts(1, 2);
    }

    @Test
    void databaseLinkFailureRollsBackFirstRootRevisionAndPreviouslyInsertedLink() {
        failedLinkInsert(null);
        assertThat(adapter.detail()).isEmpty();
        assertThat(adapter.published()).isEmpty();
        assertThat(footers.count()).isZero();
        assertThat(revisions.count()).isZero();
        assertThat(links.count()).isZero();
        // A new transaction can still create the singleton after the aborted insert.
        draft(null, content("Recovered"));
        counts(1, 2);
    }

    @Test
    void databaseLinkFailureRollsBackVersionPointerAndKeepsPublishedHistory() {
        draft(null, content("Original"));
        adapter.publish(0L, 1, 8, NOW);
        var before = adapter.detail().orElseThrow();
        failedLinkInsert(before.footer().version());
        assertThat(adapter.detail()).contains(before);
        assertThat(adapter.published()).contains(before.revision());
        assertThat(history(0, 10).getContent()).containsExactly(before.revision());
        counts(1, 2);
        var recovered = draft(before.footer().version(), content("Recovered"));
        assertThat(recovered.revision().revisionNumber()).isEqualTo(2);
        assertThat(adapter.published()).contains(before.revision());
    }

    @Test
    void deferredConstraintAtCommitIsClassifiedAfterCallbackAndRollsBackWholeDraft() {
        jdbc.execute("create sequence operations.store_footer_commit_probe");
        try {
            jdbc.execute("""
                create function operations.store_footer_deferred_conflict() returns trigger language plpgsql as $$
                begin
                    perform nextval('operations.store_footer_commit_probe');
                    raise exception 'synthetic deferred footer conflict'
                        using errcode = '23505', constraint = 'uk_store_footer_revision_footer_id_revision_number';
                end;
                $$
                """);
            jdbc.execute("""
                create constraint trigger store_footer_deferred_conflict
                after insert on operations.store_footer_revision deferrable initially deferred
                for each row execute function operations.store_footer_deferred_conflict()
                """);
            conflict(() -> draft(null, content("Commit conflict")));
            assertThat(jdbc.queryForObject("select is_called from operations.store_footer_commit_probe", Boolean.class)).isTrue();
            assertThat(adapter.detail()).isEmpty();
            assertThat(footers.count()).isZero();
            assertThat(revisions.count()).isZero();
            assertThat(links.count()).isZero();
        } finally {
            jdbc.execute("drop trigger if exists store_footer_deferred_conflict on operations.store_footer_revision");
            jdbc.execute("drop function if exists operations.store_footer_deferred_conflict()");
            jdbc.execute("drop sequence operations.store_footer_commit_probe");
        }
        draft(null, content("Recovered"));
        counts(1, 2);
    }

    @Test
    void publishedSnapshotAndPagedHistoryRemainImmutableThroughReplacementAndRestoration() {
        draft(null, content("Original"));
        assertThat(adapter.detail().orElseThrow().revision().content().links()).extracting(Link::displayOrder).containsExactly(0, 10);
        adapter.publish(0L, 1, 8, NOW.plusSeconds(1));
        var published = adapter.published().orElseThrow();
        assertThat(published.createdBy()).isEqualTo(7L);
        assertThat(published.createdAt()).isEqualTo(NOW);
        assertThat(published.content().copyrightText()).isEqualTo(new Text("국문 Original", "English Original"));
        assertThat(published.content().links()).extracting(Link::visible).containsExactly(false, true);
        assertThat(published.content().links().get(0).labels()).isEqualTo(new Text("숨김 Original", "Hidden Original"));
        var same = draft(1L, published.content());
        assertThat(same.revision().id()).isNotEqualTo(published.id());
        assertThat(same.footer().version()).isEqualTo(2L);
        var empty = draft(2L, new StoreFooterContent(new Text(null, null), List.of()));
        assertThat(empty.revision().publishedAt()).isNull();
        assertThat(adapter.published()).contains(published);
        var beforeRetry = adapter.detail().orElseThrow();
        assertThat(adapter.publish(0L, 1, 9, NOW.plusSeconds(9))).isEqualTo(beforeRetry);
        assertThat(adapter.detail()).contains(beforeRetry);
        assertThat(adapter.published()).contains(published);
        var page = history(0, 2);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(row -> row.revisionNumber()).containsExactly(3, 2);
        assertThat(page.getContent()).flatExtracting(row -> row.content().links()).extracting(Link::displayOrder).containsExactly(0, 10);
        assertThat(history(1, 2).getContent()).containsExactly(published);
        adapter.publish(empty.footer().version(), 3, 9, NOW.plusSeconds(2));
        assertThat(adapter.published().orElseThrow().content().links()).isEmpty();
        var restored = draft(adapter.detail().orElseThrow().footer().version(), published.content());
        adapter.publish(restored.footer().version(), 4, 10, NOW.plusSeconds(3));
        assertThat(adapter.published().orElseThrow().content()).isEqualTo(published.content());
        assertThat(history(3, 1).getContent()).containsExactly(published);
        assertThat(history(0, 10).getContent()).extracting(row -> row.revisionNumber()).containsExactly(4, 3, 2, 1);
        counts(4, 6);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-11-06T00:00:00.123456789Z, 2026-11-06T00:00:00.123456Z",
        "2026-11-06T00:00:00.999999999Z, 2026-11-06T00:00:00.999999Z",
        "2026-11-06T00:00:01.000000001Z, 2026-11-06T00:00:01Z"
    })
    void microsecondTimestampsMatchCommittedReadsAndIdempotentResponse(String input, String expected) {
        var processor = processorAt(Instant.parse(input));
        var unordered = content("Precision");
        var ordered = new StoreFooterContent(unordered.copyrightText(), unordered.links().reversed());
        var saved = processor.save(7, new StoreFooterCommands.SaveDraft(null, ordered));
        assertThat(saved.revision().createdAt()).isEqualTo(Instant.parse(expected));
        assertThat(processor.detail()).isEqualTo(saved);
        assertThat(saved.revision().content().links()).extracting(Link::displayOrder).containsExactly(0, 10);
        var published = processor.publish(8, new StoreFooterCommands.Publish(saved.footer().version(), 1));
        assertThat(published.revision().publishedAt()).isEqualTo(Instant.parse(expected));
        assertThat(processor.detail()).isEqualTo(published);
        assertThat(adapter.published()).contains(published.revision());
        assertThat(processorAt(Instant.parse(input).plusSeconds(10)).publish(9, new StoreFooterCommands.Publish(0L, 1))).isEqualTo(published);
        var stored = jdbc.queryForObject("select created_at from operations.store_footer_revision", java.time.OffsetDateTime.class);
        assertThat(stored.toInstant()).isEqualTo(Instant.parse(expected));
        var publication = jdbc.queryForObject("select published_at from operations.store_footer_revision", java.time.OffsetDateTime.class);
        assertThat(publication.toInstant()).isEqualTo(Instant.parse(expected));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("constraintViolations")
    void postgresEnforcesSnapshotConstraintsWithoutMutatingCommittedState(String sql, String constraint, String sqlState) {
        draft(null, content("Constraints"));
        var before = adapter.detail().orElseThrow();
        assertThatThrownBy(() -> jdbc.update(sql)).satisfies(failure -> databaseFailure(failure, constraint, sqlState));
        assertThat(adapter.detail()).contains(before);
        assertThat(history(0, 10).getContent()).containsExactly(before.revision());
        assertThat(adapter.published()).isEmpty();
        counts(1, 2);
    }

    private static Stream<Arguments> constraintViolations() {
        return Stream.of(
            Arguments.of("update operations.store_footer set id = 2", "ck_store_footer_singleton", "23514"),
            Arguments.of("update operations.store_footer set latest_revision = 0", "ck_store_footer_pointers", "23514"),
            Arguments.of("update operations.store_footer set published_revision = 0", "ck_store_footer_pointers", "23514"),
            Arguments.of("update operations.store_footer set published_revision = 2", "ck_store_footer_pointers", "23514"),
            Arguments.of("update operations.store_footer set version = -1", "ck_store_footer_version", "23514"),
            Arguments.of("update operations.store_footer_revision set revision_number = 0", "ck_store_footer_revision_number", "23514"),
            Arguments.of("update operations.store_footer_revision set footer_id = 2", "ck_store_footer_revision_root", "23514"),
            Arguments.of("update operations.store_footer_revision set published_by = 8", "ck_store_footer_revision_publication", "23514"),
            Arguments.of("update operations.store_footer_revision set published_at = current_timestamp", "ck_store_footer_revision_publication", "23514"),
            Arguments.of("insert into operations.store_footer_revision (id, footer_id, revision_number, created_by, created_at) "
                + "select id + 100000, footer_id, revision_number, created_by, created_at from operations.store_footer_revision",
                "uk_store_footer_revision_footer_id_revision_number", "23505"),
            Arguments.of("update operations.store_footer_link set display_order = 0 where display_order = 10",
                "uk_store_footer_link_revision_id_display_order", "23505"),
            Arguments.of("update operations.store_footer_link set display_order = -1 where display_order = 0", "ck_store_footer_link_order", "23514"),
            Arguments.of("update operations.store_footer_link set display_order = 1000 where display_order = 10", "ck_store_footer_link_order", "23514"),
            Arguments.of("update operations.store_footer_link set type = 'INVALID'", "ck_store_footer_link_type", "23514"),
            Arguments.of("update operations.store_footer_link set label_ko = null", "label_ko", "23502"),
            Arguments.of("update operations.store_footer_link set visible = null", "visible", "23502"),
            Arguments.of("update operations.store_footer_link set label_en = repeat('x', 81)", "character varying(80)", "22001"),
            Arguments.of("update operations.store_footer_link set url = repeat('x', 2049)", "character varying(2048)", "22001"),
            Arguments.of("update operations.store_footer_revision set copyright_en = repeat('x', 201)", "character varying(200)", "22001")
        );
    }

    private void failedLinkInsert(Long version) {
        var valid = content("Rollback").links().get(0);
        var invalid = new Link("INVALID", new Text("예시", "Example"), "https://links.example.com/invalid", 20, false);
        var content = new StoreFooterContent(new Text(null, null), List.of(valid, invalid));
        // Distinct IDs reach PostgreSQL; the second INSERT fails a CHECK after the first link INSERT.
        assertThatThrownBy(() -> draft(version, content))
            .isNotInstanceOf(StoreFooterException.class)
            .satisfies(failure -> databaseFailure(failure, "ck_store_footer_link_type", "23514"));
    }

    private void databaseFailure(Throwable failure, String constraint, String sqlState) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOfSatisfying(SQLException.class, sql -> {
            assertThat(sql.getSQLState()).isEqualTo(sqlState);
            assertThat(sql.getMessage()).contains(constraint);
        });
    }

    private StoreFooterQueryResults.Detail draft(Long version, StoreFooterContent content) {
        return adapter.saveDraft(version, content, ids.incrementAndGet(),
            content.links().stream().map(link -> ids.incrementAndGet()).toList(), 7, NOW);
    }

    private StoreFooterContent content(String text) {
        // Deliberately unsorted, bilingual, synthetic snapshots; hidden links also participate in ordering.
        return new StoreFooterContent(new Text("국문 " + text, "English " + text), List.of(
            new Link("CUSTOM", new Text("공개 " + text, "Visible " + text), "https://links.example.com/visible", 10, true),
            new Link("CUSTOM", new Text("숨김 " + text, "Hidden " + text), "https://links.example.com/hidden", 0, false)));
    }

    private StoreFooterProcessor processorAt(Instant now) {
        return new StoreFooterProcessor(adapter, new StoreFooterUrlPolicy(Set.of("links.example.com")),
            Clock.fixed(now, ZoneOffset.UTC), generator);
    }

    private Page<StoreFooterRevision> history(int page, int size) {
        return adapter.revisions(PageRequest.of(page, size, Sort.by("revisionNumber").descending()));
    }

    private void counts(long revisionCount, long linkCount) {
        assertThat(footers.count()).isEqualTo(1);
        assertThat(revisions.count()).isEqualTo(revisionCount);
        assertThat(links.count()).isEqualTo(linkCount);
    }

    private void conflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(StoreFooterException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(StoreFooterErrorCode.CONFLICT);
            assertThat(exception.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exception.getErrorCode().getCode()).isEqualTo("STORE_FOOTER_020");
        });
    }

    private List<Boolean> race(Callable<?> first, Callable<?> second) throws Exception {
        var bothRead = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        Advised repository = (Advised) footers;
        MethodInterceptor synchronizeRootReads = invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("findById") && Long.valueOf(1).equals(invocation.getArguments()[0])) {
                // Synchronize reads, independent of whether writes use annotations or TransactionTemplate.
                bothRead.countDown();
                assertThat(release.await(15, TimeUnit.SECONDS)).as("release both root readers").isTrue();
            }
            return result;
        };
        var executor = Executors.newFixedThreadPool(2);
        repository.addAdvice(0, synchronizeRootReads);
        try {
            var a = executor.submit(() -> outcome(first));
            var b = executor.submit(() -> outcome(second));
            assertThat(bothRead.await(15, TimeUnit.SECONDS)).as("both transactions reached their root read").isTrue();
            release.countDown();
            var outcomes = List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder(true, false);
            return outcomes;
        } finally {
            release.countDown();
            executor.shutdownNow();
            try {
                assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).as("race workers stopped before cleanup").isTrue();
            } finally {
                repository.removeAdvice(synchronizeRootReads);
            }
        }
    }

    private boolean outcome(Callable<?> operation) throws Exception {
        try {
            operation.call();
            return true;
        } catch (StoreFooterException exception) {
            assertThat(exception.getErrorCode()).isEqualTo(StoreFooterErrorCode.CONFLICT);
            assertThat(exception.getErrorCode().getCode()).isEqualTo("STORE_FOOTER_020");
            assertThat(exception.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
            return false;
        }
    }
}
