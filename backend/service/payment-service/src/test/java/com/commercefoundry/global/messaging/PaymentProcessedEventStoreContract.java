package com.commercefoundry.global.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.application.mapper.PaymentMapperImpl;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** SPI 계약 시험이다. 실제 회신 Processor나 Kafka 소비는 연결하지 않는다. */
@DataJpaTest
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, JpaProcessedEventStore.class,
    PaymentMapperImpl.class, PaymentProcessedEventStoreContract.Ids.class})
@TestPropertySource(properties = {"spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class PaymentProcessedEventStoreContract {
    static final String GROUP = "commerce-foundry.test.payment.order-payment-result";
    static final Instant NOW = Instant.parse("2026-11-04T00:00:00.123456Z");
    @Autowired JpaProcessedEventStore store;
    @Autowired ProcessedEventRepository repository;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PaymentMapperImpl mapper;

    @AfterEach
    void clean() {
        repository.deleteAllInBatch();
        jdbc.update("delete from payment.payment");
    }

    @Test
    void recordIsMandatoryAndJoinsCallerTransactionWhileExistsWorksOutsideIt() {
        UUID event = UUID.randomUUID();
        assertThatThrownBy(() -> store.record(GROUP, event, "order-paid", NOW)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(store.exists(GROUP, event)).isFalse();
        tx().executeWithoutResult(status -> {
            store.record(GROUP, event, "order-paid", NOW);
            assertThat(store.exists(GROUP, event)).isTrue();
        });
        assertThat(store.exists(GROUP, event)).isTrue();
        assertThat(store.exists(GROUP + "-other", event)).isFalse();
        tx().executeWithoutResult(status -> store.record(GROUP + "-other", event, "order-paid", NOW));
        assertThat(repository.findAll()).hasSize(2).allSatisfy(row -> {
            assertThat(row.getProcessedAt()).isEqualTo(NOW);
            assertThat(row.getEventType()).isEqualTo("order-paid");
            assertThat(row.getCreatedAt()).isNotNull();
            assertThat(row.getUpdatedAt()).isNotNull();
        });
    }

    @Test
    void duplicateInboxFailureRollsBackAlreadyFlushedBusinessAndReverseOrderAlsoRollsBack() {
        seedPayment(9000000101L);
        UUID event = UUID.randomUUID();
        tx().executeWithoutResult(status -> store.record(GROUP, event, "order-paid", NOW));
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            jdbc.update("update payment.payment set version=version+1 where id=9000000101");
            store.record(GROUP, event, "order-paid", NOW);
            repository.flush();
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(version(9000000101L)).isZero();
        UUID rolledBack = UUID.randomUUID();
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            store.record(GROUP, rolledBack, "order-paid", NOW);
            repository.flush();
            jdbc.update("update payment.payment set version=version+1 where id=9000000101");
            throw new IllegalStateException("forced transaction rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.exists(GROUP, rolledBack)).isFalse();
        assertThat(version(9000000101L)).isZero();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyCommitsOneInboxAndOnlyItsBusinessChange() throws Exception {
        seedPayment(9000000101L);
        seedPayment(9000000102L);
        UUID event = UUID.randomUUID();
        CyclicBarrier ready = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(9000000101L, 9000000102L).stream().map(id -> pool.submit(() -> {
                try {
                    tx().executeWithoutResult(status -> {
                        assertThat(store.exists(GROUP, event)).isFalse();
                        jdbc.update("update payment.payment set version=version+1 where id=?", id);
                        try {
                            ready.await(10, TimeUnit.SECONDS);
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                        store.record(GROUP, event, "order-paid", NOW);
                        repository.flush();
                    });
                    return true;
                } catch (org.springframework.dao.DataIntegrityViolationException expected) {
                    return false;
                }
            })).toList();
            assertThat(List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        }
        assertThat(repository.count()).isEqualTo(1);
        assertThat(version(9000000101L) + version(9000000102L)).isEqualTo(1);
    }

    @Test
    void cleanupIsStrictOrderedLimitedRepeatableAndTransactional() {
        tx().executeWithoutResult(status -> {
            // Persist in reverse ID order: the query must use ID as its tie breaker.
            for (long id : List.of(30L, 20L, 10L)) {
                repository.save(ProcessedEventEntity.builder().id(id).consumerGroup(GROUP).eventId(UUID.randomUUID())
                    .eventType("order-paid").processedAt(NOW.minusSeconds(1)).build());
            }
            store.record(GROUP, UUID.randomUUID(), "order-paid", NOW);
            store.record(GROUP, UUID.randomUUID(), "order-paid", NOW.plusSeconds(1));
        });
        assertThat(store.deleteProcessedBefore(NOW, 1)).isEqualTo(1);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            assertThat(store.deleteProcessedBefore(NOW, 2)).isEqualTo(2);
            throw new IllegalStateException("forced cleanup rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.count()).isEqualTo(4); // The earlier committed batch remains deleted.
        assertThat(store.deleteProcessedBefore(NOW, 1)).isEqualTo(1);
        assertThat(repository.existsById(10L)).isFalse();
        assertThat(repository.existsById(20L)).isFalse();
        assertThat(repository.existsById(30L)).isTrue();
        assertThat(store.deleteProcessedBefore(NOW, 2)).isEqualTo(1);
        assertThat(store.deleteProcessedBefore(NOW, 2)).isZero();
        assertThat(repository.findAll()).hasSize(2).allSatisfy(row -> assertThat(row.getProcessedAt()).isAfterOrEqualTo(NOW));
    }

    private void seedPayment(long id) {
        tx().executeWithoutResult(status -> em.persist(mapper.toEntity(PaymentFixtures.ready(id, id + 100))));
    }

    private long version(long id) {
        return jdbc.queryForObject("select version from payment.payment where id=?", Long.class, id);
    }

    private TransactionTemplate tx() {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setTimeout(15);
        return template;
    }

    @TestConfiguration
    static class Ids {
        @Bean SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(0, 0);
        }
    }
}
