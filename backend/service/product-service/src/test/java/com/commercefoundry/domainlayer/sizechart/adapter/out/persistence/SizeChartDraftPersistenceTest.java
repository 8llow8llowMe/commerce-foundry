package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity.SizeChartDraftEntity;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartDraftRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** H2 exercises mappings, constraints and actual versioned writes; PostgreSQL DDL remains an integration requirement. */
@DataJpaTest(properties = "spring.datasource.url=jdbc:h2:mem:size-chart-draft;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class, SizeChartDraftRepositoryAdapter.class})
class SizeChartDraftPersistenceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant EXPIRY = NOW.plusSeconds(604800);
    private static final String HASH = "a".repeat(64);
    private static final UUID KEY = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private SizeChartDraftRepositoryPort port;

    @Autowired
    private com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository.SizeChartDraftRepository repository;

    @Test
    void inputAndApplyReceiptRoundTripWithVersionedManagedUpdates() {
        SizeChartDraft inserted = port.insert(draft(1L, 100L, KEY));
        assertThat(inserted.version()).isZero();
        entityManager.clear();
        SizeChartDraft found = port.findByRequestedByMemberIdAndIdempotencyKey(100L, KEY).orElseThrow();
        assertThat(found).isEqualTo(inserted);
        assertThat(port.findByRequestedByMemberIdAndIdempotencyKey(101L, KEY)).isEmpty();
        assertThat(port.findById(999L)).isEmpty();

        SizeChartDraft bound = port.update(found.bindJob(200L, NOW), NOW);
        SizeChartDraft.Candidate candidate = SizeChartDraft.Candidate.of("{\"rows\":[{\"label\":\"S\"}]}");
        SizeChartDraft ready = port.update(bound.ready(candidate, NOW), NOW);
        SizeChartDraft.ApplyReceipt receipt = new SizeChartDraft.ApplyReceipt(HASH, "{\"chartId\":\"300\",\"revision\":1}", 101L, NOW);
        SizeChartDraft applied = port.update(ready.apply(candidate, receipt, NOW), NOW);
        entityManager.clear();

        assertThat(port.findById(1L)).contains(applied);
        assertThat(applied.version()).isEqualTo(3L);
        assertThat(applied.input().baseChartId()).isEqualTo(50L);
        assertThat(applied.input().baseChartRevision()).isEqualTo(0L);
        assertThat(applied.applyReceipt()).isEqualTo(receipt);
        assertThatThrownBy(() -> applied.apply(candidate, receipt, EXPIRY)).isInstanceOf(IllegalStateException.class);
        assertThat(entityManager.find(SizeChartDraftEntity.class, 1L).getCreatedAt()).isNotNull();
    }

    @Test
    void differentActorCanUseSameUuidAndNullJobIdsDoNotConflict() {
        port.insert(draft(1L, 100L, KEY));
        port.insert(draft(2L, 101L, KEY));
        entityManager.clear();
        assertThat(port.findByRequestedByMemberIdAndIdempotencyKey(100L, KEY).orElseThrow().id()).isEqualTo(1L);
        assertThat(port.findByRequestedByMemberIdAndIdempotencyKey(101L, KEY).orElseThrow().id()).isEqualTo(2L);
    }

    @Test
    void duplicateIdIsAnInsertConflictRatherThanAnOverwrite() {
        port.insert(draft(1L, 100L, KEY));
        entityManager.clear();
        assertThatThrownBy(() -> port.insert(draft(1L, 101L, UUID.randomUUID())))
            .rootCause().hasMessageContaining("PRIMARY KEY");
    }

    @Test
    void duplicateActorAndKeyAreRejected() {
        port.insert(draft(1L, 100L, KEY));
        assertThatThrownBy(() -> port.insert(draft(2L, 100L, KEY)))
            .rootCause().hasMessageContaining("uk_size_chart_draft_requested_by_member_id_idempotency_key");
    }

    @Test
    void jobCanBelongToOnlyOneDraft() {
        SizeChartDraft first = port.insert(draft(1L, 100L, KEY));
        SizeChartDraft second = port.insert(draft(2L, 101L, KEY));
        port.update(first.bindJob(200L, NOW), NOW);
        assertThatThrownBy(() -> port.update(second.bindJob(200L, NOW), NOW))
            .rootCause().hasMessageContaining("uk_size_chart_draft_job_id");
    }

    @Test
    void staleDomainSnapshotIsRejectedEvenAfterPersistenceContextIsCleared() {
        SizeChartDraft old = port.insert(draft(1L, 100L, KEY));
        port.update(old.bindJob(200L, NOW), NOW);
        entityManager.clear();
        assertThatThrownBy(() -> port.update(old.cancel(NOW), NOW)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void managedUpdateRejectsForgedInputAndLifecycleJump() {
        SizeChartDraft original = port.insert(draft(1L, 100L, KEY));
        SizeChartDraft forged = new SizeChartDraft(1L, original.input(), 200L, SizeChartDraft.Candidate.of("{}"),
            SizeChartDraftStatus.READY, original.version(), EXPIRY, null);
        assertThatThrownBy(() -> port.update(forged, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(port.findById(1L)).contains(original);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentManagedUpdatesCannotBothCommit() {
        try (EntityManager seed = entityManagerFactory.createEntityManager();
             EntityManager first = entityManagerFactory.createEntityManager();
             EntityManager second = entityManagerFactory.createEntityManager()) {
            seed.getTransaction().begin();
            seed.persist(entityBuilder().id(9000L).build());
            seed.getTransaction().commit();
            try {
                first.getTransaction().begin();
                second.getTransaction().begin();
                SizeChartDraftEntity winner = first.find(SizeChartDraftEntity.class, 9000L);
                SizeChartDraftEntity loser = second.find(SizeChartDraftEntity.class, 9000L);
                winner.change(winner.toDomain().cancel(NOW), NOW);
                first.getTransaction().commit();
                loser.change(loser.toDomain().fail(NOW), NOW);
                assertThatThrownBy(second::flush).isInstanceOf(OptimisticLockException.class);
            } finally {
                if (first.getTransaction().isActive()) {
                    first.getTransaction().rollback();
                }
                if (second.getTransaction().isActive()) {
                    second.getTransaction().rollback();
                }
                seed.getTransaction().begin();
                seed.clear();
                seed.remove(seed.find(SizeChartDraftEntity.class, 9000L));
                seed.getTransaction().commit();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void baseChartIdAndRevisionMustBeBothPresentOrBothAbsent(boolean idPresent) {
        SizeChartDraftEntity entity = entityBuilder().baseChartId(idPresent ? 50L : null).baseChartRevision(idPresent ? null : 0L).build();
        entityManager.persist(entity);
        assertThatThrownBy(entityManager::flush).rootCause().hasMessageContaining("ck_size_chart_draft_base_chart_pair");
    }

    @Test
    void baseChartCanBeAbsentAndCapturedRevisionCanBeZero() {
        entityManager.persistAndFlush(entityBuilder().build());
        entityManager.clear();
        SizeChartDraft found = port.findById(1L).orElseThrow();
        assertThat(found.input().baseChartId()).isNull();
        assertThat(found.input().baseChartRevision()).isNull();
        assertThat(found.input().templateRevision()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void candidateJsonAndHashCannotBePartial(boolean jsonPresent) {
        entityManager.persist(entityBuilder().jobId(200L).status(SizeChartDraftStatus.READY)
            .candidateJson(jsonPresent ? "{}" : null).candidateHash(jsonPresent ? null : HASH).build());
        assertThatThrownBy(entityManager::flush).rootCause().hasMessageContaining("ck_size_chart_draft_candidate");
    }

    @Test
    void appliedStateRequiresCompleteReceipt() {
        SizeChartDraft.Candidate candidate = SizeChartDraft.Candidate.of("{}");
        entityManager.persist(entityBuilder().jobId(200L).status(SizeChartDraftStatus.APPLIED)
            .candidateJson(candidate.json()).candidateHash(candidate.hash()).applyHash(HASH).build());
        assertThatThrownBy(entityManager::flush).rootCause().hasMessageContaining("ck_size_chart_draft_apply_receipt");
    }

    @Test
    void sourceJsonBoundIsAlsoEnforcedByDatabase() {
        entityManager.persist(entityBuilder().sourceJson("x".repeat(SizeChartDraft.MAX_JSON_LENGTH + 1)).build());
        assertThatThrownBy(entityManager::flush).rootCause().hasMessageContaining("ck_size_chart_draft_source_json_length");
    }

    @Test
    void cleanupIsBoundedOrderedAndDoesNotPurgeUnexpiredRows() {
        entityManager.persistAndFlush(entityBuilder().id(3L).idempotencyKey(UUID.randomUUID()).expiresAt(EXPIRY.minusSeconds(1)).build());
        entityManager.persistAndFlush(entityBuilder().id(2L).idempotencyKey(UUID.randomUUID()).expiresAt(EXPIRY).build());
        entityManager.persistAndFlush(entityBuilder().id(1L).idempotencyKey(UUID.randomUUID()).expiresAt(EXPIRY).build());
        entityManager.persistAndFlush(entityBuilder().id(4L).idempotencyKey(UUID.randomUUID()).expiresAt(EXPIRY.plusSeconds(1)).build());
        assertThat(repository.findExpiredIds(EXPIRY, org.springframework.data.domain.PageRequest.of(0, 2))).containsExactly(3L, 1L);
        assertThat(port.deleteExpiredBatch(EXPIRY, 2)).isEqualTo(2);
        assertThat(port.findById(3L)).isEmpty();
        assertThat(port.findById(1L)).isEmpty();
        assertThat(port.findById(2L)).isPresent();
        assertThat(port.findById(4L)).isPresent();
        assertThat(port.deleteExpiredBatch(EXPIRY, 2)).isEqualTo(1);
        assertThat(port.deleteExpiredBatch(EXPIRY, 2)).isZero();
        assertThat(repository.findAll()).extracting(SizeChartDraftEntity::getId).containsExactly(4L);
    }

    @Test
    void duplicateCleanupSelectionsAndForgedLiveIdCannotDeletePrematurely() {
        port.insert(draft(1L, 100L, KEY));
        entityManager.persistAndFlush(entityBuilder().id(2L).idempotencyKey(UUID.randomUUID()).expiresAt(EXPIRY.plusSeconds(1)).build());
        var first = repository.findExpiredIds(EXPIRY, org.springframework.data.domain.PageRequest.of(0, 10));
        var second = repository.findExpiredIds(EXPIRY, org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(first).containsExactly(1L);
        assertThat(repository.deleteExpiredIds(first, EXPIRY)).isEqualTo(1);
        assertThat(repository.deleteExpiredIds(second, EXPIRY)).isZero();
        assertThat(repository.deleteExpiredIds(java.util.List.of(2L), EXPIRY)).isZero();
        assertThat(port.findById(2L)).isPresent();
        assertThat(port.findByRequestedByMemberIdAndIdempotencyKey(100L, KEY)).isEmpty();
        assertThat(port.insert(draft(3L, 100L, KEY)).id()).isEqualTo(3L);
    }

    private static SizeChartDraft draft(long id, long actor, UUID key) {
        SizeChartDraft.Input input = new SizeChartDraft.Input(10L, 20L, 0L, actor, key, HASH, HASH,
            "{\"text\":\"S 50cm\"}", "{\"unit\":\"CM\",\"columns\":[]}", 50L, 0L);
        return SizeChartDraft.request(id, input, EXPIRY);
    }

    private static SizeChartDraftEntity.SizeChartDraftEntityBuilder entityBuilder() {
        return SizeChartDraftEntity.builder().id(1L).productId(10L).templateId(20L).templateRevision(0L)
            .requestedByMemberId(100L).idempotencyKey(KEY).requestHash(HASH).inputHash(HASH)
            .sourceJson("{}").templateJson("{}").status(SizeChartDraftStatus.REQUESTED).expiresAt(EXPIRY);
    }
}
