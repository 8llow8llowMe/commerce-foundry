package com.commercefoundry.global.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * messaging-core {@code ProcessedEventStore} 계약을 product 스키마의 실제 PostgreSQL 로 본다 — 기록 · 조회(그룹별), 같은 키의 유니크 위반,
 * 업무 트랜잭션 합류, 보존 기간 정리. 스키마는 DDL 스냅숏 + {@code ddl-auto=validate} 다(uuid · timestamptz 포함).
 * Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=product",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class, JpaProcessedEventStore.class, JpaProcessedEventStoreTest.ProcessedEventStoreTestConfig.class})
class JpaProcessedEventStoreTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    private static final String GROUP = "commerce-foundry.test.product.order-stock-release";
    private static final Instant NOW = Instant.parse("2026-10-28T09:00:00Z");

    @Autowired
    private JpaProcessedEventStore processedEventStore;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Test
    @DisplayName("기록한 이벤트는 그 그룹에서만 처리한 것으로 보인다 — 처리 시각 · 종류가 남는다")
    void recordThenExists() {
        UUID eventId = UUID.randomUUID();

        processedEventStore.record(GROUP, eventId, "order-expired", NOW);

        assertThat(processedEventStore.exists(GROUP, eventId)).isTrue();
        assertThat(processedEventStore.exists(GROUP + "-other", eventId)).isFalse();
        assertThat(processedEventStore.exists(GROUP, UUID.randomUUID())).isFalse();
        ProcessedEventEntity row = processedEventRepository.findAll().getFirst();
        assertThat(row.getEventId()).isEqualTo(eventId);
        assertThat(row.getEventType()).isEqualTo("order-expired");
        assertThat(row.getProcessedAt()).isEqualTo(NOW);
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 그룹 · 이벤트를 두 번 기록하면 flush 때 유니크 키(uk_processed_event_consumer_group_event_id) 위반이다 — 다른 그룹은 따로 기록된다")
    void duplicateViolatesUniqueKey() {
        UUID eventId = UUID.randomUUID();
        processedEventStore.record(GROUP, eventId, "order-expired", NOW);
        processedEventStore.record(GROUP + "-other", eventId, "order-expired", NOW);
        processedEventRepository.flush();

        processedEventStore.record(GROUP, eventId, "order-expired", NOW);

        assertThatThrownBy(() -> processedEventRepository.flush()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("정리: 보존 기간 전에 처리한 기록만 limit 건씩 지운다")
    void deleteProcessedBefore() {
        processedEventStore.record(GROUP, UUID.randomUUID(), "order-expired", NOW.minusSeconds(3600));
        processedEventStore.record(GROUP, UUID.randomUUID(), "order-expired", NOW.minusSeconds(3600));
        processedEventStore.record(GROUP, UUID.randomUUID(), "order-canceled", NOW);
        processedEventRepository.flush();

        assertThat(processedEventStore.deleteProcessedBefore(NOW.minusSeconds(60), 1)).isEqualTo(1);
        assertThat(processedEventStore.deleteProcessedBefore(NOW.minusSeconds(60), 10)).isEqualTo(1);
        assertThat(processedEventStore.deleteProcessedBefore(NOW.minusSeconds(60), 10)).isZero();
        assertThat(processedEventRepository.findAll()).singleElement().extracting(ProcessedEventEntity::getEventType).isEqualTo("order-canceled");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("record 는 업무 트랜잭션 안에서만 — 트랜잭션 밖에서 부르면 실패하고 행이 남지 않는다. exists 는 밖에서도 읽는다")
    void recordRequiresTransaction() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> processedEventStore.record(GROUP, eventId, "order-expired", NOW)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(processedEventStore.exists(GROUP, eventId)).isFalse();
        assertThat(processedEventRepository.count()).isZero();
    }

    @TestConfiguration
    static class ProcessedEventStoreTestConfig {

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(0, 0);
        }
    }
}
