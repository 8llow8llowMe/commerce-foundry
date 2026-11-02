package com.commercefoundry.domainlayer.knowledge.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.repository.KnowledgeDocumentRepository;
import com.commercefoundry.domainlayer.knowledge.adapter.out.persistence.repository.KnowledgeRevisionRepository;
import com.commercefoundry.domainlayer.knowledge.application.command.KnowledgeContentCommand;
import com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeErrorCode;
import com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeException;
import com.commercefoundry.domainlayer.knowledge.application.mapper.KnowledgeMapperImpl;
import com.commercefoundry.domainlayer.knowledge.application.port.out.KnowledgeRepositoryPort;
import com.commercefoundry.domainlayer.knowledge.application.service.processor.KnowledgeProcessor;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeCategory;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeDocument;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeRevision;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeStatus;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeType;
import com.commercefoundry.global.config.SupportServiceFeaturesConfig;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** H2와 PostgreSQL이 같은 계약을 실행한다. 테스트 자체는 트랜잭션 밖이며 Processor 또는 tx가 커밋·롤백한다. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({SupportServiceFeaturesConfig.class, KnowledgeRepositoryAdapter.class, KnowledgeMapperImpl.class,
    KnowledgeProcessor.class, KnowledgePersistenceContract.IdConfig.class})
abstract class KnowledgePersistenceContract {

    static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    @Autowired KnowledgeProcessor processor;
    @Autowired KnowledgeRepositoryPort port;
    @Autowired KnowledgeDocumentRepository documents;
    @Autowired KnowledgeRevisionRepository revisions;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean KnowledgeRepositoryAdapter adapter;
    TransactionTemplate tx;

    @TestConfiguration
    static class IdConfig {
        @Bean SnowflakeIdGenerator knowledgeIds() {
            return new SnowflakeIdGenerator(1, 1);
        }
    }

    @BeforeEach
    void clean() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            revisions.deleteAllInBatch();
            documents.deleteAllInBatch();
        });
    }

    @AfterEach
    void clearSpy() {
        reset(adapter);
    }

    static KnowledgeRevision.Content content(Instant from, Instant to) {
        return new KnowledgeRevision.Content("배송 안내", "상품 배송 정책입니다.", KnowledgeCategory.SHIPPING, from, to);
    }

    KnowledgeRevision approved(KnowledgeLocale locale) {
        KnowledgeRevision draft = processor.create(KnowledgeType.FAQ, locale, content(NOW, NOW.plusSeconds(60)));
        return processor.approve(draft.documentId(), 1, draft.version(), 99, NOW);
    }

    KnowledgeRevision load(long documentId, int number) {
        return tx.execute(status -> port.findRevision(documentId, number).orElseThrow());
    }

    KnowledgeDocument document(long id) {
        return tx.execute(status -> port.findDocument(id).orElseThrow());
    }

    @Test
    void draftPreservesPublishedAndReplacementArchivesOldRevision() {
        KnowledgeRevision first = approved(KnowledgeLocale.KO);
        processor.publish(first.documentId(), 1, NOW);
        KnowledgeRevision draft = processor.createDraft(first.documentId(), content(NOW, null));
        assertThat(processor.published(KnowledgeLocale.KO, NOW, 10)).extracting(KnowledgeRevision::revisionNumber).containsExactly(1);
        assertThat(document(first.documentId()).latestRevision()).isEqualTo(2);
        processor.approve(first.documentId(), 2, draft.version(), 99, NOW);
        processor.publish(first.documentId(), 2, NOW);
        assertThat(load(first.documentId(), 1).status()).isEqualTo(KnowledgeStatus.ARCHIVED);
        assertThat(processor.published(KnowledgeLocale.KO, NOW, 10)).extracting(KnowledgeRevision::revisionNumber).containsExactly(2);
        assertThatThrownBy(() -> processor.publish(first.documentId(), 1, NOW)).isInstanceOf(KnowledgeException.class);
    }

    @Test
    void publicationIsIdempotentAndCurrentArchiveClearsPointer() {
        KnowledgeRevision draft = approved(KnowledgeLocale.KO);
        KnowledgeRevision published = processor.publish(draft.documentId(), 1, NOW);
        KnowledgeDocument current = document(draft.documentId());
        assertThat(processor.publish(draft.documentId(), 1, NOW)).isEqualTo(published);
        assertThat(document(draft.documentId()).version()).isEqualTo(current.version());
        processor.archive(draft.documentId(), 1);
        assertThat(document(draft.documentId()).publishedRevision()).isNull();
        assertThat(processor.published(KnowledgeLocale.KO, NOW, 10)).isEmpty();
        assertThatThrownBy(() -> processor.publish(draft.documentId(), 1, NOW)).isInstanceOf(KnowledgeException.class);
    }

    @Test
    void publishedQueryRequiresExactLocaleCurrentPointerAndPeriodAndHonorsLimit() {
        for (KnowledgeLocale locale : List.of(KnowledgeLocale.KO, KnowledgeLocale.KO, KnowledgeLocale.EN)) {
            KnowledgeRevision approved = approved(locale);
            processor.publish(approved.documentId(), 1, NOW);
        }
        approved(KnowledgeLocale.EN); // 승인만 받은 문서는 검색되지 않는다.
        assertThat(processor.published(KnowledgeLocale.KO, NOW, 1)).hasSize(1);
        assertThat(processor.published(KnowledgeLocale.KO, NOW, 100)).hasSize(2);
        assertThat(processor.published(KnowledgeLocale.EN, NOW, 100)).hasSize(1);
        assertThat(processor.published(KnowledgeLocale.EN, NOW.minusSeconds(1), 100)).isEmpty();
        assertThat(processor.published(KnowledgeLocale.EN, NOW.plusSeconds(60), 100)).isEmpty();
        assertThatThrownBy(() -> processor.published(KnowledgeLocale.KO, NOW, 101)).isInstanceOf(KnowledgeException.class);
    }

    @Test
    void futurePublicationAndOldApprovalAreRejected() {
        KnowledgeRevision future = processor.create(KnowledgeType.POLICY, KnowledgeLocale.KO, content(NOW.plusSeconds(1), null));
        processor.approve(future.documentId(), 1, future.version(), 99, NOW);
        assertThatThrownBy(() -> processor.publish(future.documentId(), 1, NOW)).isInstanceOf(KnowledgeException.class);
        KnowledgeRevision first = processor.create(KnowledgeType.FAQ, KnowledgeLocale.KO, content(NOW, null));
        processor.createDraft(first.documentId(), content(NOW, null));
        processor.approve(first.documentId(), 1, first.version(), 99, NOW); // 과거 승인본은 허용하되 게시 금지.
        assertThatThrownBy(() -> processor.publish(first.documentId(), 1, NOW)).isInstanceOf(KnowledgeException.class);
        assertThat(document(first.documentId()).publishedRevision()).isNull();
    }

    @Test
    void staleEditVersionAndActualStaleMergeBecomeConflict() {
        KnowledgeRevision draft = processor.create(KnowledgeType.FAQ, KnowledgeLocale.KO, content(NOW, null));
        processor.edit(draft.documentId(), 1, draft.version(),
            new KnowledgeRevision.Content("수정된 배송 안내", "본문도 수정했습니다.", KnowledgeCategory.SHIPPING, NOW, null));
        assertThatThrownBy(() -> processor.approve(draft.documentId(), 1, draft.version(), 99, NOW))
            .isInstanceOfSatisfying(KnowledgeException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(KnowledgeErrorCode.CONCURRENTLY_CHANGED));
        assertThatThrownBy(() -> tx.execute(status -> port.saveRevision(draft.edit(content(NOW, null)))))
            .isInstanceOfSatisfying(KnowledgeException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(KnowledgeErrorCode.CONCURRENTLY_CHANGED));
    }

    @Test
    void replacementFailureRollsBackPointerAndBothRevisionStates() {
        KnowledgeRevision first = approved(KnowledgeLocale.KO);
        processor.publish(first.documentId(), 1, NOW);
        KnowledgeRevision second = processor.createDraft(first.documentId(), content(NOW, null));
        processor.approve(first.documentId(), 2, second.version(), 99, NOW);
        KnowledgeDocument before = document(first.documentId());
        AtomicBoolean oldArchiveFlushed = new AtomicBoolean();
        doAnswer(call -> {
            KnowledgeRevision revision = call.getArgument(0);
            if (revision.status() == KnowledgeStatus.ARCHIVED) {
                Object saved = call.callRealMethod();
                oldArchiveFlushed.set(true);
                return saved;
            }
            if (revision.status() == KnowledgeStatus.PUBLISHED && revision.revisionNumber() == 2) {
                throw new IllegalStateException("테스트용 신규 게시본 저장 실패");
            }
            return call.callRealMethod();
        }).when(adapter).saveRevision(any());
        assertThatThrownBy(() -> processor.publish(first.documentId(), 2, NOW)).isInstanceOf(KnowledgeException.class);
        assertThat(oldArchiveFlushed).isTrue();
        assertThat(document(first.documentId())).isEqualTo(before);
        assertThat(load(first.documentId(), 1).status()).isEqualTo(KnowledgeStatus.PUBLISHED);
        assertThat(load(first.documentId(), 2).status()).isEqualTo(KnowledgeStatus.APPROVED);
    }

    @Test
    void twoTransactionsReadingSameRootVersionHaveOnePublicationWinner() throws Exception {
        KnowledgeRevision approved = approved(KnowledgeLocale.KO);
        CyclicBarrier bothRead = new CyclicBarrier(2);
        doAnswer(call -> {
            Object snapshot = call.callRealMethod();
            bothRead.await(10, TimeUnit.SECONDS);
            return snapshot;
        }).when(adapter).findDocument(anyLong());
        doAnswer(call -> {
            Object snapshot = call.callRealMethod();
            bothRead.await(10, TimeUnit.SECONDS);
            return snapshot;
        }).when(adapter).findRevision(anyLong(), anyInt());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> publishOutcome(approved.documentId()));
            var second = executor.submit(() -> publishOutcome(approved.documentId()));
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("PUBLISHED", "CONCURRENTLY_CHANGED");
        } finally {
            reset(adapter);
        }
        assertThat(document(approved.documentId()).publishedRevision()).isEqualTo(1);
        assertThat(processor.published(KnowledgeLocale.KO, NOW, 100)).hasSize(1);
    }

    private String publishOutcome(long id) {
        try {
            processor.publish(id, 1, NOW);
            return "PUBLISHED";
        } catch (KnowledgeException exception) {
            return exception.getErrorCode().name();
        }
    }

    /** H2 이름 접미사는 운영의 정확한 제약 이름 판정에 포함하지 않는다. PostgreSQL만 업무 충돌로 번역한다. */
    Class<? extends RuntimeException> uniqueViolationType() {
        return DataIntegrityViolationException.class;
    }

    @Test
    void uniqueDocumentRevisionConstraintIsEnforced() {
        KnowledgeRevision existing = processor.create(KnowledgeType.FAQ, KnowledgeLocale.KO, content(NOW, null));
        assertThatThrownBy(() -> tx.execute(status -> port.saveRevision(KnowledgeRevision.draft(existing.id() + 1, existing.documentId(), 1, existing.content()))))
            .isInstanceOf(uniqueViolationType());
    }

    @Test
    void adminProjectionUsesLatestRevisionAndIdenticalCountFilters() {
        KnowledgeRevision first = processor.create(KnowledgeType.FAQ, KnowledgeLocale.KO, content(NOW, null));
        processor.create(KnowledgeType.POLICY, KnowledgeLocale.KO, content(NOW, null));
        processor.create(KnowledgeType.FAQ, KnowledgeLocale.EN, content(NOW, null));
        KnowledgeRevision last = processor.create(KnowledgeType.FAQ, KnowledgeLocale.KO, content(NOW, null));
        var next = processor.createDraftAdmin(first.documentId(), document(first.documentId()).version(), adminContent("Latest title"));
        var page = processor.listAdmin(KnowledgeType.FAQ, KnowledgeLocale.KO, 0, 1);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).singleElement().satisfies(row -> assertThat(row.documentId()).isEqualTo(last.documentId()));
        var second = processor.listAdmin(KnowledgeType.FAQ, KnowledgeLocale.KO, 1, 1).getContent().getFirst();
        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(second.latestRevision()).isEqualTo(2);
        assertThat(second.title()).isEqualTo("Latest title");
        assertThat(second.documentVersion()).isEqualTo(next.document().version());
        assertThat(second.revisionVersion()).isEqualTo(next.revision().version());
        assertThat(processor.listAdmin(null, null, 0, 100).getTotalElements()).isEqualTo(4);
        assertThat(processor.listAdmin(null, KnowledgeLocale.KO, 0, 1).getTotalElements()).isEqualTo(3);
        assertThat(processor.listAdmin(KnowledgeType.POLICY, null, 0, 1).getTotalElements()).isEqualTo(1);
    }

    @Test
    void strictAdminGuardsRootAndRevisionVersionsAndKeepsRetryIdempotence() {
        KnowledgeRevision first = approved(KnowledgeLocale.KO);
        KnowledgeDocument root = document(first.documentId());
        assertConflict(() -> processor.publishAdmin(root.id(), 1, first.version(), root.version() + 1, NOW));
        assertConflict(() -> processor.publishAdmin(root.id(), 1, first.version() + 1, root.version(), NOW));
        assertThat(load(root.id(), 1).status()).isEqualTo(KnowledgeStatus.APPROVED);
        var published = processor.publishAdmin(root.id(), 1, first.version(), root.version(), NOW);
        assertThat(processor.publishAdmin(root.id(), 1, -100L, -100L, NOW)).isEqualTo(published);
        assertConflict(() -> processor.archiveAdmin(root.id(), 1, published.revision().version(), root.version()));
        assertConflict(() -> processor.archiveAdmin(root.id(), 1, -100L, published.document().version()));
        processor.archiveAdmin(root.id(), 1, published.revision().version(), published.document().version());
        processor.archiveAdmin(root.id(), 1, -100L, -100L);
        assertThat(document(root.id()).publishedRevision()).isNull();
        assertThat(load(root.id(), 1).status()).isEqualTo(KnowledgeStatus.ARCHIVED);
    }

    @Test
    void strictNewDraftRetryConflictsAndOldDraftCannotBeEditedOrApproved() {
        var created = processor.createAdmin(KnowledgeType.POLICY, KnowledgeLocale.KO, adminContent("Initial title"));
        var next = processor.createDraftAdmin(created.document().id(), created.document().version(), adminContent("Second title"));
        assertConflict(() -> processor.createDraftAdmin(created.document().id(), created.document().version(), adminContent("Retry")));
        assertThatThrownBy(() -> processor.editAdmin(created.document().id(), 1, created.revision().version(), adminContent("Old edit")))
            .isInstanceOfSatisfying(KnowledgeException.class, e -> assertThat(e.getErrorCode()).isEqualTo(KnowledgeErrorCode.INVALID_TRANSITION));
        assertThatThrownBy(() -> processor.approveAdmin(created.document().id(), 1, created.revision().version(), 99, NOW))
            .isInstanceOfSatisfying(KnowledgeException.class, e -> assertThat(e.getErrorCode()).isEqualTo(KnowledgeErrorCode.INVALID_TRANSITION));
        var edited = processor.editAdmin(created.document().id(), 2, next.revision().version(), adminContent("Edited"));
        assertConflict(() -> processor.approveAdmin(created.document().id(), 2, next.revision().version(), 99, NOW));
        var approved = processor.approveAdmin(created.document().id(), 2, edited.revision().version(), 99, NOW);
        assertThat(approved.revision().approvedBy()).isEqualTo(99);
        assertThat(processor.getAdmin(created.document().id()).revision()).isEqualTo(approved.revision());
    }

    @Test
    void rawAdminContentIsValidatedInsideProcessorAndNotFoundIsPreserved() {
        assertThatThrownBy(() -> processor.createAdmin(KnowledgeType.FAQ, KnowledgeLocale.KO,
            new KnowledgeContentCommand(" ", "body", KnowledgeCategory.SHIPPING, NOW, null)))
            .isInstanceOfSatisfying(KnowledgeException.class, e -> assertThat(e.getErrorCode()).isEqualTo(KnowledgeErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> processor.getAdmin(1))
            .isInstanceOfSatisfying(KnowledgeException.class, e -> assertThat(e.getErrorCode()).isEqualTo(KnowledgeErrorCode.NOT_FOUND));
        assertThat(processor.listAdmin(null, null, 0, 20).getTotalElements()).isZero();
    }

    private KnowledgeContentCommand adminContent(String title) {
        return new KnowledgeContentCommand(title, "Admin knowledge body", KnowledgeCategory.SHIPPING, NOW, null);
    }

    private void assertConflict(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(KnowledgeException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(KnowledgeErrorCode.CONCURRENTLY_CHANGED));
    }
}
