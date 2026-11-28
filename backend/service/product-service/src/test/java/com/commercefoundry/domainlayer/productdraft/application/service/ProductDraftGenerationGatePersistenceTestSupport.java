package com.commercefoundry.domainlayer.productdraft.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.ProductDraftRepositoryAdapter;
import com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.repository.ProductDraftRepository;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftGenerationPort;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftGenerationProcessor;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
import com.commercefoundry.global.properties.ProductDraftProperties;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 매장 전체 동시 생성 3건 게이트(#359 B3, D13)를 실제 DB 잠금 · 쿼리로 확인한다 — 동시에 넷이 새 회차를 시작해도 셋만 되고, 회차를 시작한 지 자리 시간(30분)이
 * 지난 초안 · 보관 기간이 지난 초안은 자리를 차지하지 않는다.
 */
@TestPropertySource(properties = {
    "spring.jpa.properties.hibernate.default_schema=product",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "product-draft.generation-enabled=true"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({QuerydslConfigurer.class, ProductDraftRepositoryAdapter.class, ProductDraftGenerationProcessor.class,
    ProductDraftGenerationGatePersistenceTestSupport.Properties.class})
abstract class ProductDraftGenerationGatePersistenceTestSupport {

    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");
    private static final Duration SLOT_TTL = ProductDraftProperties.DEFAULT_GENERATION_SLOT_TTL;
    private static final String HASH = "a".repeat(64);
    private static final AtomicLong IDS = new AtomicLong(500);
    private static final ProductDraftFacts FACTS = new ProductDraftFacts("운영자 사실", null, null, null, null, null, null, null, null, null);

    @Autowired private ProductDraftGenerationProcessor processor;
    @Autowired private ProductDraftRepositoryAdapter repository;
    @Autowired private ProductDraftRepository entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager tx;
    @MockitoBean private ProductDraftGenerationPort generationPort;
    @MockitoBean private Clock clock;

    @BeforeEach
    void reset() {
        given(clock.instant()).willReturn(NOW);
        entities.deleteAllInBatch();
        jdbc.update("delete from product.product_draft_generation_gate");
        jdbc.update("insert into product.product_draft_generation_gate (id) values (1)");
    }

    @Test
    void fourConcurrentStartsTakeAtMostThreeSlots() throws Exception {
        List<Long> drafts = List.of(requested(), requested(), requested(), requested());
        CyclicBarrier start = new CyclicBarrier(drafts.size());
        ExecutorService pool = Executors.newFixedThreadPool(drafts.size());
        List<Future<ProductDraftErrorCode>> outcomes = new ArrayList<>();
        try {
            for (long id : drafts) {
                Callable<ProductDraftErrorCode> claim = () -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        processor.claim(id, 11);
                        return null;
                    } catch (ProductDraftException exception) {
                        return exception.getErrorCode();
                    }
                };
                outcomes.add(pool.submit(claim));
            }
            List<ProductDraftErrorCode> codes = new ArrayList<>();
            for (Future<ProductDraftErrorCode> outcome : outcomes) {
                codes.add(outcome.get(30, TimeUnit.SECONDS));
            }

            assertThat(codes).filteredOn(code -> code == null).hasSize(3);
            assertThat(codes).filteredOn(code -> code != null).containsExactly(ProductDraftErrorCode.GENERATION_SLOTS_FULL);
            assertThat(entities.findAll()).filteredOn(row -> row.getGenerationNo() == 1).hasSize(3);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void staleAndExpiredDraftsDoNotHoldSlots() {
        generating(NOW.minus(SLOT_TTL).minusSeconds(1), NOW.plusSeconds(3600));
        generating(NOW.minus(SLOT_TTL).minusSeconds(1), NOW.plusSeconds(3600));
        generating(NOW.minusSeconds(60), NOW);
        generating(NOW.minusSeconds(60), NOW.plusSeconds(3600));
        generating(NOW.minusSeconds(60), NOW.plusSeconds(3600));

        long third = requested();
        assertThat(processor.claim(third, 11).generation().generationNo()).isEqualTo(1);

        long fourth = requested();
        assertThatThrownBy(() -> processor.claim(fourth, 11)).isInstanceOfSatisfying(ProductDraftException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(ProductDraftErrorCode.GENERATION_SLOTS_FULL));
        assertThat(repository.findById(fourth).orElseThrow().generation().claimed()).isFalse();
    }

    @Test
    void regenerationClearsThePreviousResultInTheSameRow() {
        long id = generating(NOW.minusSeconds(60), NOW.plusSeconds(3600));
        new TransactionTemplate(tx).executeWithoutResult(ignored -> {
            ProductDraft current = repository.findById(id).orElseThrow();
            repository.updateGenerationResult(current.finishGeneration(NOW, null, "PRODUCT_DRAFT_AI_FAILED"));
        });

        ProductDraft claimed = processor.claimRegeneration(id, 12, 1);

        assertThat(claimed.generation().generationNo()).isEqualTo(2);
        var row = entities.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ProductDraftStatus.REQUESTED);
        assertThat(row.getGenerationNo()).isEqualTo(2);
        assertThat(row.getGenerationRequestedByMemberId()).isEqualTo(12L);
        assertThat(row.getJobId()).isNull();
        assertThat(row.getFailureCode()).isNull();
        assertThat(row.getResultJson()).isNull();
    }

    /** 생성 대기 · 회차 0 초안. */
    private long requested() {
        long id = IDS.incrementAndGet();
        new TransactionTemplate(tx).executeWithoutResult(ignored -> repository.insert(ProductDraft.request(id, 11, FACTS, List.of(), NOW.plusSeconds(3600))));
        return id;
    }

    /** {@code claimedAt} 에 1회차를 시작해 작업이 붙은 생성 중 초안. */
    private long generating(Instant claimedAt, Instant expiresAt) {
        long id = IDS.incrementAndGet();
        var transaction = new TransactionTemplate(tx);
        transaction.executeWithoutResult(ignored -> repository.insert(ProductDraft.request(id, 11, FACTS, List.of(), expiresAt)));
        transaction.executeWithoutResult(ignored -> {
            ProductDraft saved = repository.findById(id).orElseThrow();
            repository.updateGeneration(saved.claimGeneration(11, HASH, claimedAt).bindJob(id, 1, HASH, id + 1000));
        });
        return id;
    }

    @TestConfiguration
    @EnableConfigurationProperties(ProductDraftProperties.class)
    static class Properties {
    }
}
