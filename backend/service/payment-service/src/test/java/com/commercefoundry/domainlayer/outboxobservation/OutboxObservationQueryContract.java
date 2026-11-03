package com.commercefoundry.domainlayer.outboxobservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.outboxobservation.adapter.out.persistence.OutboxObservationRepositoryAdapter;
import com.commercefoundry.global.messaging.OutboxEntity;
import com.commercefoundry.global.messaging.OutboxRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
abstract class OutboxObservationQueryContract {

    static final Instant NOW = Instant.parse("2026-11-03T01:02:03.123456Z");

    @Autowired OutboxRepository repository;
    @Autowired OutboxObservationRepositoryAdapter adapter;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void emptyAndFutureOnlyReturnZeroAndNull() {
        assertThat(adapter.observeReady(NOW).readyUnpublishedCount()).isZero();
        assertThat(adapter.observeReady(NOW).oldestReadyAvailableAt()).isNull();
        row(1, NOW.plusNanos(1_000), null);
        assertThat(adapter.observeReady(NOW).readyUnpublishedCount()).isZero();
        assertThat(adapter.observeReady(NOW).oldestReadyAvailableAt()).isNull();
    }

    @Test
    void boundaryAndPastAreIncludedPublishedAndFutureAreExcludedWithOneSelect() {
        row(1, NOW.minusSeconds(60), null);
        row(2, NOW, null);
        row(3, NOW.plusSeconds(1), null);
        row(4, NOW.minusSeconds(120), NOW);
        var statistics = entityManagerFactory.unwrap(SessionFactoryImplementor.class).getStatistics();
        statistics.clear();

        var result = adapter.observeReady(NOW);

        assertThat(result.readyUnpublishedCount()).isEqualTo(2);
        assertThat(result.oldestReadyAvailableAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        repository.markPublished(1L, NOW);
        var next = adapter.observeReady(NOW);
        assertThat(next.readyUnpublishedCount()).isEqualTo(1);
        assertThat(next.oldestReadyAvailableAt()).isEqualTo(NOW);
        repository.markPublished(2L, NOW);
        assertThat(adapter.observeReady(NOW).readyUnpublishedCount()).isZero();
        assertThat(adapter.observeReady(NOW).oldestReadyAvailableAt()).isNull();
    }

    private void row(long id, Instant availableAt, Instant publishedAt) {
        repository.saveAndFlush(OutboxEntity.builder().id(id).aggregateType("payment").aggregateId("sentinel-aggregate")
            .eventType("payment-approved").schemaVersion(1).payload("sentinel-payload").availableAt(availableAt)
            .publishedAt(publishedAt).publishAttempts(0).build());
    }
}
