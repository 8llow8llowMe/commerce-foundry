package com.commercefoundry.domainlayer.productdraft.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.presenter.ProductDraftAdminPresenter;
import com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.ProductDraftRepositoryAdapter;
import com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.entity.ProductDraftEntity;
import com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.repository.ProductDraftRepository;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftGenerationResultQueryPort;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftGenerationResultQueryResult;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftGenerationResultQueryResult.Status;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftGenerationProcessor;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftGenerationResultProcessor;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftImageProcessor;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftProcessor;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftCandidate;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftGeneration;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.global.properties.ProductDraftProperties;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = {
    "spring.jpa.properties.hibernate.default_schema=product",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "product-draft.generation-enabled=true"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class, ProductDraftRepositoryAdapter.class,
    ProductDraftAdminFacade.class, ProductDraftProcessor.class, ProductDraftGenerationResultProcessor.class,
    ProductDraftAdminPresenter.class, ProductDraftRefreshPersistenceTestSupport.Properties.class})
abstract class ProductDraftRefreshPersistenceTestSupport {

    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(60);
    private static final String HASH = "a".repeat(64);
    private static final AtomicLong IDS = new AtomicLong(100);
    private static final ProductDraftFacts FACTS = new ProductDraftFacts("운영자 사실", null, null, null, null, null, null, null,
        List.of("단추"), List.of("ＡＢＣ"));

    @Autowired private ProductDraftAdminFacade facade;
    @Autowired private ProductDraftRepository entities;
    @Autowired private PlatformTransactionManager tx;
    @MockitoSpyBean private ProductDraftRepositoryAdapter repository;
    @MockitoBean private ProductDraftGenerationResultQueryPort remote;
    @MockitoBean private ProductAdminAuditProcessor audit;
    @MockitoBean private ProductDraftGenerationProcessor generation;
    @MockitoBean private ProductDraftImageProcessor images;
    @MockitoBean private MediaUrlPolicy media;
    @MockitoBean private SnowflakeIdGenerator ids;
    @MockitoBean private Clock clock;

    @BeforeEach
    void fixedTime() {
        given(clock.instant()).willReturn(NOW);
    }

    @ParameterizedTest
    @EnumSource(Status.class)
    void stateAndReplay(Status status) {
        long id = seed();
        given(remote.result(any())).willAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return result(call.getArgument(0), status, "ABC");
        });

        var response = facade.refresh(id, 11);
        ProductDraftEntity saved = row(id);
        assertThat(response.status().code()).isEqualTo(saved.getStatus().name());
        assertThat(facade.getDraft(id)).isEqualTo(response);
        assertThat(response.facts().nameHint()).isEqualTo("운영자 사실");
        if (status == Status.PENDING) {
            assertThat(saved.getVersion()).isEqualTo(1L);
            assertThat(saved.getResultJson()).isNull();
            assertThat(saved.getFailureCode()).isNull();
        } else {
            assertThat(saved.getVersion()).isEqualTo(2L);
            if (status == Status.SUCCEEDED) {
                assertThat(saved.getStatus()).isEqualTo(ProductDraftStatus.READY);
                assertThat(response.result().suggestion().name()).isEqualTo("ABC");
                assertThat(response.result().warnings()).extracting("code").containsExactly("MUST_INCLUDE_MISSING", "MUST_AVOID_USED");
                assertThat(saved.getResultJson()).contains("MUST_AVOID_USED");
            } else {
                assertThat(saved.getStatus()).isEqualTo(ProductDraftStatus.FAILED);
                assertThat(saved.getResultJson()).isNull();
                assertThat(saved.getFailureCode()).isEqualTo(switch (status) {
                    case FAILED -> "PRODUCT_DRAFT_AI_BUDGET_EXCEEDED";
                    case CANCELLED -> ProductDraft.GENERATION_CANCELLED;
                    case EXPIRED -> ProductDraft.GENERATION_RESULT_EXPIRED;
                    default -> throw new AssertionError(status);
                });
                assertThat(response.failureNotice()).isNotBlank().doesNotContain(saved.getFailureCode());
            }
            assertThat(facade.refresh(id, 12)).isEqualTo(response);
            assertThat(row(id).getVersion()).isEqualTo(2L);
        }
        verify(remote, times(1)).result(any());
        verify(audit).productDraftRefreshed(11, id, true);
    }

    @Test
    void concurrentRefreshesReturnTheSameCommittedWinner() throws Exception {
        long id = seed();
        given(remote.result(any())).willAnswer(call -> result(call.getArgument(0), Status.SUCCEEDED, Thread.currentThread().getName()));
        var barrier = new CyclicBarrier(2);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            barrier.await(10, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(repository).updateGenerationResult(any());

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> facade.refresh(id, 11));
            var second = workers.submit(() -> facade.refresh(id, 12));
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS)).isEqualTo(facade.getDraft(id));
        }
        assertThat(row(id).getVersion()).isEqualTo(2L);
        verify(remote, times(2)).result(any());
        verify(repository, times(2)).updateGenerationResult(any());
    }

    @Test
    void rejectionCommitsDuringRemoteLookupAndCannotBeOverwritten() {
        long id = seed();
        given(remote.result(any())).willAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(facade.reject(id, 12).status().code()).isEqualTo("REJECTED");
            return result(call.getArgument(0), Status.SUCCEEDED, "늦은 결과");
        });

        assertThat(facade.refresh(id, 11).status().code()).isEqualTo("REJECTED");
        assertThat(row(id).getResultJson()).isNull();
        assertThat(row(id).getVersion()).isEqualTo(2L);
    }

    @Test
    void remoteWaitCrossingExpiryDoesNotWrite() {
        long id = seed();
        given(remote.result(any())).willAnswer(call -> {
            given(clock.instant()).willReturn(EXPIRES);
            return result(call.getArgument(0), Status.SUCCEEDED, "늦은 결과");
        });

        assertCode(() -> facade.refresh(id, 11), ProductDraftErrorCode.DRAFT_EXPIRED);
        assertUnchanged(id);
    }

    @Test
    void aNewerBindingCannotBeOverwritten() {
        long id = seed();
        given(remote.result(any())).willAnswer(call -> {
            ProductDraft original = call.getArgument(0);
            new TransactionTemplate(tx).executeWithoutResult(ignored -> {
                ProductDraft newer = new ProductDraft(id, ProductDraftStatus.GENERATING, 11, FACTS, List.of(),
                    null, null, EXPIRES, original.createdAt(), new ProductDraftGeneration(2, 12L, NOW, "b".repeat(64), id + 2000));
                repository.updateGeneration(newer);
            });
            return result(original, Status.SUCCEEDED, "지난 회차");
        });

        assertCode(() -> facade.refresh(id, 11), ProductDraftErrorCode.DRAFT_BUSY);
        assertThat(row(id).getGenerationNo()).isEqualTo(2);
        assertThat(row(id).getResultJson()).isNull();
        assertThat(row(id).getVersion()).isEqualTo(2L);
    }

    @Test
    void aWrongRemoteBindingCannotBeWritten() {
        long id = seed();
        given(remote.result(any())).willReturn(new ProductDraftGenerationResultQueryResult(
            id, 1, id + 1001, HASH, Status.CANCELLED, null, null));

        assertCode(() -> facade.refresh(id, 11), ProductDraftErrorCode.GENERATION_CONTRACT_VIOLATION);
        assertUnchanged(id);
        verify(audit).productDraftRefreshed(11, id, false);
    }

    @Test
    void remoteErrorAndPostFlushFailureLeaveTheRowRetryable() {
        long id = seed();
        given(remote.result(any())).willThrow(new ProductDraftException(ProductDraftErrorCode.GENERATION_UNAVAILABLE))
            .willAnswer(call -> result(call.getArgument(0), Status.SUCCEEDED, "재조회 결과"));
        doAnswer(call -> {
            call.callRealMethod();
            throw new QueryTimeoutException("flush 이후 롤백 확인");
        }).doCallRealMethod().when(repository).updateGenerationResult(any());

        assertCode(() -> facade.refresh(id, 11), ProductDraftErrorCode.GENERATION_UNAVAILABLE);
        assertUnchanged(id);
        assertCode(() -> facade.refresh(id, 11), ProductDraftErrorCode.GENERATION_UNAVAILABLE);
        assertUnchanged(id);

        assertThat(facade.refresh(id, 11).status().code()).isEqualTo("READY");
        assertThat(row(id).getVersion()).isEqualTo(2L);
        verify(remote, times(3)).result(any());
        verify(audit, times(2)).productDraftRefreshed(11, id, false);
        verify(audit).productDraftRefreshed(11, id, true);
    }

    private long seed() {
        long id = IDS.incrementAndGet();
        var transaction = new TransactionTemplate(tx);
        transaction.executeWithoutResult(ignored -> repository.insert(ProductDraft.request(id, 11, FACTS, List.of(), EXPIRES)));
        transaction.executeWithoutResult(ignored -> {
            ProductDraft saved = repository.findById(id).orElseThrow();
            repository.updateGeneration(saved.claimGeneration(11, HASH, NOW).bindJob(id, 1, HASH, id + 1000));
        });
        return id;
    }

    private ProductDraftEntity row(long id) {
        return entities.findById(id).orElseThrow();
    }

    private void assertUnchanged(long id) {
        assertThat(row(id).getStatus()).isEqualTo(ProductDraftStatus.GENERATING);
        assertThat(row(id).getVersion()).isEqualTo(1L);
        assertThat(row(id).getResultJson()).isNull();
        assertThat(row(id).getFailureCode()).isNull();
    }

    private static ProductDraftGenerationResultQueryResult result(ProductDraft draft, Status status, String name) {
        var generation = draft.generation();
        var candidate = status == Status.SUCCEEDED ? new ProductDraftCandidate(
            new ProductDraftCandidate.Suggestion(name, "설명", List.of(), "제목", "소개", List.of()), List.of(), List.of()) : null;
        var failure = status == Status.FAILED ? ProductDraftGenerationResultQueryResult.FailureCode.PRODUCT_DRAFT_AI_BUDGET_EXCEEDED : null;
        return new ProductDraftGenerationResultQueryResult(draft.id(), generation.generationNo(), generation.jobId(), generation.inputHash(),
            status, failure, candidate);
    }

    private static void assertCode(Runnable call, ProductDraftErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ProductDraftException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @TestConfiguration
    @EnableConfigurationProperties(ProductDraftProperties.class)
    static class Properties {
    }
}
