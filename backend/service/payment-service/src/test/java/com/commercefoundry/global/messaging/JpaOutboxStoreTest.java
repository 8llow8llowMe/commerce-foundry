package com.commercefoundry.global.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.messaging.outbox.OutboxStore;
import com.commercefoundry.messaging.support.OutboxStoreContract;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * messaging-core {@code OutboxStore} 계약({@link OutboxStoreContract} — 보낼 행 조회 순서 · 조건, 한 번만 표시, 실패 기록, 보존 기간 정리)을 payment 스키마의
 * 실제 PostgreSQL 로 본다. 여기서는 JPA 어댑터에 기대는 것(업무 트랜잭션 합류, 칸에 남는 값, 실패 코드 칸 길이, 지표 쿼리)을 더 본다.
 * 스키마는 DDL 스냅숏 + {@code ddl-auto=validate} 다. Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=payment",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({PaymentServiceFeaturesConfig.class, JpaOutboxStore.class, JpaOutboxStoreTest.OutboxStoreTestConfig.class})
class JpaOutboxStoreTest extends OutboxStoreContract {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");

    @Autowired
    private JpaOutboxStore outboxStore;

    @Autowired
    private OutboxRepository outboxRepository;

    @Override
    protected OutboxStore outboxStore() {
        return outboxStore;
    }

    @Override
    protected long totalRows() {
        return outboxRepository.count();
    }

    @Override
    protected Instant publishedAt(long id) {
        return outboxRepository.findById(id).orElseThrow().getPublishedAt();
    }

    @Test
    @DisplayName("발행 표시는 발행 시각 칸에 남고, 실패 기록은 시도 수와 실패 코드를 칸 길이까지 남긴다")
    void columns() {
        outboxStore.append(row("1", NOW));
        outboxStore.append(row("2", NOW));
        long published = outboxStore.findPublishable(NOW, 10).get(0).id();
        long failed = outboxStore.findPublishable(NOW, 10).get(1).id();

        outboxStore.markPublished(published, NOW);
        outboxStore.markFailed(failed, "TimeoutException");
        outboxStore.markFailed(failed, "E".repeat(150));

        assertThat(outboxRepository.findById(published).orElseThrow().getPublishedAt()).isEqualTo(NOW);
        OutboxEntity failedRow = outboxRepository.findById(failed).orElseThrow();
        assertThat(failedRow.getPublishAttempts()).isEqualTo(2);
        assertThat(failedRow.getLastErrorCode()).hasSize(JpaOutboxStore.ERROR_CODE_MAX_LENGTH);
    }

    @Test
    @DisplayName("지표 쿼리 — 미발행 행 수와 가장 오래된 미발행 행의 발행 가능 시각")
    void metricsQueries() {
        outboxStore.append(row("1", NOW.minusSeconds(60)));
        outboxStore.append(row("2", NOW));
        outboxStore.markPublished(outboxStore.findPublishable(NOW, 10).getFirst().id(), NOW);

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
