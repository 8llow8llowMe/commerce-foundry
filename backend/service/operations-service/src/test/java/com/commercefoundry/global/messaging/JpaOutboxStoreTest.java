package com.commercefoundry.global.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.global.config.OperationsServiceFeaturesConfig;
import com.commercefoundry.messaging.outbox.OutboxRow;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * messaging-core {@code OutboxStore} 계약을 operations 스키마의 실제 PostgreSQL 로 본다 — 업무 트랜잭션 합류, 보낼 행 조회 순서 · 조건,
 * 한 번만 표시, 실패 기록, 보존 기간 정리. 스키마는 DDL 스냅숏 + {@code ddl-auto=validate} 다. Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=operations",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({OperationsServiceFeaturesConfig.class, JpaOutboxStore.class, JpaOutboxStoreTest.OutboxStoreTestConfig.class})
class JpaOutboxStoreTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    private static final Instant NOW = Instant.parse("2026-10-24T09:00:00Z");

    @Autowired
    private JpaOutboxStore outboxStore;

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    @DisplayName("보낼 행: 미발행이고 발행 가능 시각이 지난 행만 아이디 오름차순으로 limit 건까지 — 봉투 · 시도 수를 그대로 돌려준다")
    void findPublishable() {
        outboxStore.append(row("1", NOW.minusSeconds(30)));
        outboxStore.append(row("2", NOW));
        outboxStore.append(row("3", NOW.plusSeconds(60)));
        outboxStore.append(row("4", NOW.minusSeconds(60)));

        List<OutboxRow> publishable = outboxStore.findPublishable(NOW, 10);

        assertThat(publishable).extracting(OutboxRow::aggregateId).containsExactly("1", "2", "4");
        assertThat(publishable).extracting(OutboxRow::id).isSorted();
        assertThat(publishable.getFirst().envelopeJson()).isEqualTo("{\"jobId\":\"1\"}");
        assertThat(publishable.getFirst().eventType()).isEqualTo("job-run");
        assertThat(publishable.getFirst().schemaVersion()).isEqualTo(1);
        assertThat(publishable.getFirst().publishAttempts()).isZero();
        assertThat(outboxStore.findPublishable(NOW, 2)).hasSize(2);
    }

    @Test
    @DisplayName("발행 표시는 한 번만 바뀐다 — 두 번째는 false 이고, 표시된 행은 다시 보낼 행이 아니다")
    void markPublishedOnce() {
        outboxStore.append(row("1", NOW));
        long id = outboxStore.findPublishable(NOW, 10).getFirst().id();

        assertThat(outboxStore.markPublished(id, NOW)).isTrue();
        assertThat(outboxStore.markPublished(id, NOW.plusSeconds(1))).isFalse();
        assertThat(outboxStore.findPublishable(NOW.plusSeconds(10), 10)).isEmpty();
        assertThat(outboxRepository.findById(id).orElseThrow().getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("발행 실패는 시도 수를 올리고 실패 코드를 칸 길이까지 남긴다 — 행은 다음 주기에 다시 보낼 행이다")
    void markFailed() {
        outboxStore.append(row("1", NOW));
        long id = outboxStore.findPublishable(NOW, 10).getFirst().id();

        outboxStore.markFailed(id, "TimeoutException");
        outboxStore.markFailed(id, "E".repeat(150));

        OutboxEntity failed = outboxRepository.findById(id).orElseThrow();
        assertThat(failed.getPublishAttempts()).isEqualTo(2);
        assertThat(failed.getLastErrorCode()).hasSize(JpaOutboxStore.ERROR_CODE_MAX_LENGTH);
        assertThat(outboxStore.findPublishable(NOW, 10)).extracting(OutboxRow::publishAttempts).containsExactly(2);
    }

    @Test
    @DisplayName("정리: 보존 기간 전에 발행된 행만 limit 건씩 지운다 — 미발행 · 최근 발행 행은 남는다")
    void deletePublishedBefore() {
        for (int index = 1; index <= 4; index++) {
            outboxStore.append(row(String.valueOf(index), NOW));
        }
        List<OutboxRow> rows = outboxStore.findPublishable(NOW, 10);
        outboxStore.markPublished(rows.get(0).id(), NOW.minusSeconds(3600));
        outboxStore.markPublished(rows.get(1).id(), NOW.minusSeconds(3600));
        outboxStore.markPublished(rows.get(2).id(), NOW);

        assertThat(outboxStore.deletePublishedBefore(NOW.minusSeconds(60), 1)).isEqualTo(1);
        assertThat(outboxStore.deletePublishedBefore(NOW.minusSeconds(60), 10)).isEqualTo(1);
        assertThat(outboxStore.deletePublishedBefore(NOW.minusSeconds(60), 10)).isZero();
        assertThat(outboxRepository.count()).isEqualTo(2);
        assertThat(outboxRepository.countByPublishedAtIsNull()).isEqualTo(1);
        assertThat(outboxRepository.findOldestUnpublishedAvailableAt()).isEqualTo(NOW);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("append 는 업무 트랜잭션 안에서만 — 트랜잭션 밖에서 부르면 실패하고 행이 남지 않는다")
    void appendRequiresTransaction() {
        assertThatThrownBy(() -> outboxStore.append(row("1", NOW))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxRepository.count()).isZero();
    }

    private static OutboxRow row(String aggregateId, Instant availableAt) {
        return new OutboxRow(null, "job", aggregateId, "job-run", 1, "{\"jobId\":\"" + aggregateId + "\"}", availableAt, 0);
    }

    @TestConfiguration
    static class OutboxStoreTestConfig {

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(0, 0);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
