package com.commercefoundry.global.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.global.config.OperationsServiceFeaturesConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #476 측정 전용 JDBC/EXPLAIN. 실제 operations DDL을 적용한 전용 컨테이너만 사용한다.
 * 후보 인덱스는 이 컨테이너에만 만들며 실행 코드·엔티티·운영 DDL을 바꾸지 않는다. 시간/플랜 모양으로 합격을 판정하지 않는다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=operations",
    "spring.jpa.show-sql=false",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import(OperationsServiceFeaturesConfig.class)
class OutboxIndexExplainTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    private static final Instant NOW = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant CUTOFF = NOW.minusSeconds(7 * 86400);
    private static final int PUBLISHED = 100_000;
    private static final int BATCH = 100;
    private static final int REPEATS = 3;
    private static final String RELAY = "select * from operations.outbox where published_at is null and available_at <= ? order by id limit 100";
    private static final String AVAILABLE_ORDER =
        "select * from operations.outbox where published_at is null and available_at <= ? order by available_at, id limit 100";
    private static final String CLEANUP = "select id from operations.outbox where published_at < ? order by published_at, id limit 100";
    private static final String COUNT = "select count(*) from operations.outbox where published_at is null";
    private static final String OLDEST = "select min(available_at) from operations.outbox where published_at is null";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OutboxRepository repository;

    private JdbcTemplate jdbc;

    @BeforeEach
    void initialize() {
        jdbc = new JdbcTemplate(dataSource);
        dropCandidates();
        jdbc.execute("truncate operations.outbox");
        assertThat(jdbc.queryForObject("select current_setting('server_version_num')::int / 10000", Integer.class)).isEqualTo(17);
    }

    @Test
    void comparePlansWithoutChangingProductionIndexesOrOrdering() throws Exception {
        Map<String, Object> environment = new LinkedHashMap<>();
        for (String setting : List.of("server_version", "shared_buffers", "work_mem", "effective_cache_size",
            "max_parallel_workers_per_gather", "random_page_cost", "seq_page_cost", "block_size")) {
            environment.put(setting, jdbc.queryForObject("select current_setting(?)", String.class, setting));
        }
        environment.put("publishedRows", PUBLISHED);
        environment.put("batchSize", BATCH);
        environment.put("unpublishedFutureFraction", "1/4");
        environment.put("payloadBytes", 1024);
        environment.put("repeatsAfterOneWarmup", REPEATS);
        environment.put("cutoff", CUTOFF.toString());
        environment.put("now", NOW.toString());
        environment.put("fixture", "published even IDs / unpublished odd IDs; available_at deliberately not ID ordered; ANALYZE, no VACUUM");
        emit("ENV", environment);
        List<Map<String, Object>> samples = new ArrayList<>();
        try {
            for (int unpublished : List.of(0, 1_000, 100_000)) {
                seed(unpublished);
                List<Long> expectedRelay = repository.findByPublishedAtIsNullAndAvailableAtLessThanEqualOrderByIdAsc(NOW, Limit.of(BATCH))
                    .stream().map(OutboxEntity::getId).toList();
                List<Long> expectedCleanup = repository.findIdsPublishedBefore(CUTOFF, Limit.of(BATCH));
                assertThat(repository.countByPublishedAtIsNull()).isEqualTo(unpublished);
                assertThat(jdbc.queryForObject("select count(*) from operations.outbox where published_at is null and available_at > ?",
                    Long.class, Timestamp.from(NOW))).isEqualTo(unpublished / 4L);
                for (String candidate : List.of("baseline", "partial_id", "partial_available_id")) {
                    configureCandidate(candidate);
                    // JDBC 측정문과 실제 JPA 저장소의 결과를 비교해 SQL 복제의 의미가 달라지면 실패시킨다.
                    assertThat(ids(RELAY, Timestamp.from(NOW))).isEqualTo(expectedRelay);
                    assertThat(ids(CLEANUP, Timestamp.from(CUTOFF))).isEqualTo(expectedCleanup);
                    assertThat(jdbc.queryForObject(COUNT, Long.class)).isEqualTo(repository.countByPublishedAtIsNull());
                    Timestamp oldest = jdbc.queryForObject(OLDEST, Timestamp.class);
                    assertThat(oldest == null ? null : oldest.toInstant()).isEqualTo(repository.findOldestUnpublishedAvailableAt());
                    measure(samples, unpublished, candidate, "relay_id", RELAY, Timestamp.from(NOW));
                    measure(samples, unpublished, candidate, "cleanup_ids", CLEANUP, Timestamp.from(CUTOFF));
                    measure(samples, unpublished, candidate, "unpublished_count", COUNT);
                    measure(samples, unpublished, candidate, "oldest_available", OLDEST);
                    if (candidate.equals("partial_available_id")) {
                        // 성능 비교용 대안일 뿐 현재 ID 순서 계약을 만족하는 쿼리로 취급하지 않는다.
                        measure(samples, unpublished, candidate, "relay_available_order_NOT_COMPATIBLE", AVAILABLE_ORDER, Timestamp.from(NOW));
                    }
                }
            }
        } finally {
            dropCandidates();
        }
        assertThat(samples).hasSize(117);
        Path report = Path.of("build/reports/outbox-index-explain.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("environment", environment, "samples", samples)));
        emit("COMPLETE", Map.of("samples", samples.size(), "report", report.toString()));
    }

    @Test
    void availableAtOrderingCanReverseEventsForTheSameAggregate() {
        jdbc.update("""
            insert into operations.outbox
                (id, aggregate_type, aggregate_id, event_type, schema_version, payload, available_at, published_at,
                 publish_attempts, version, created_at, updated_at)
            values (1, 'fixture', 'same-key', 'fixture', 1, '{}', ?, null, 0, 0, ?, ?),
                   (2, 'fixture', 'same-key', 'fixture', 1, '{}', ?, null, 0, 0, ?, ?)
            """, Timestamp.from(NOW.minusSeconds(1)), Timestamp.from(NOW), Timestamp.from(NOW),
            Timestamp.from(NOW.minusSeconds(10)), Timestamp.from(NOW), Timestamp.from(NOW));

        assertThat(ids(RELAY, Timestamp.from(NOW))).containsExactly(1L, 2L);
        assertThat(ids(AVAILABLE_ORDER, Timestamp.from(NOW))).containsExactly(2L, 1L);
    }

    private void seed(int unpublished) {
        dropCandidates();
        jdbc.execute("truncate operations.outbox");
        jdbc.update("""
            insert into operations.outbox
                (id, aggregate_type, aggregate_id, event_type, schema_version, payload, available_at, published_at,
                 publish_attempts, version, created_at, updated_at)
            select n * 2, 'fixture', 'key-' || (n % 100), 'fixture', 1, repeat('x', 1024),
                   ?::timestamptz - interval '8 days', ?::timestamptz - interval '8 days' + (n % 3600) * interval '1 second',
                   0, 0, ?::timestamptz - interval '9 days', ?::timestamptz - interval '8 days'
              from generate_series(1, ?) n
            """, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW), PUBLISHED);
        jdbc.update("""
            insert into operations.outbox
                (id, aggregate_type, aggregate_id, event_type, schema_version, payload, available_at, published_at,
                 publish_attempts, version, created_at, updated_at)
            select n * 2 - 1, 'fixture', 'key-' || (n % 100), 'fixture', 1, repeat('x', 1024),
                   ?::timestamptz + case when n % 4 = 0 then interval '60 seconds'
                                       else -((n * 17) % 60 + 1) * interval '1 second' end,
                   null, 0, 0, ?::timestamptz - interval '1 hour', ?::timestamptz - interval '1 hour'
              from generate_series(1, ?) n
            """, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW), unpublished);
        jdbc.execute("analyze operations.outbox");
        assertThat(jdbc.queryForObject("select count(*) from operations.outbox", Long.class)).isEqualTo(PUBLISHED + (long) unpublished);
    }

    private void configureCandidate(String candidate) {
        dropCandidates();
        if (candidate.equals("partial_id")) {
            jdbc.execute("create index idx_outbox_measure_unpublished_id on operations.outbox (id) where published_at is null");
        } else if (candidate.equals("partial_available_id")) {
            jdbc.execute("create index idx_outbox_measure_unpublished_available_id on operations.outbox (available_at, id) where published_at is null");
        }
        jdbc.execute("analyze operations.outbox");
    }

    private void dropCandidates() {
        jdbc.execute("drop index if exists operations.idx_outbox_measure_unpublished_id");
        jdbc.execute("drop index if exists operations.idx_outbox_measure_unpublished_available_id");
    }

    private List<Long> ids(String sql, Object... arguments) {
        return jdbc.query(sql, (result, row) -> result.getLong("id"), arguments);
    }

    private void measure(List<Map<String, Object>> samples, int unpublished, String candidate, String query, String sql, Object... arguments) {
        String explain = "explain (analyze, buffers, format json) " + sql;
        jdbc.queryForObject(explain, String.class, arguments); // 1회 워밍업. OS/DB 캐시를 강제 비우지 않는다.
        for (int repeat = 1; repeat <= REPEATS; repeat++) {
            JsonNode plan = JSON.readTree(jdbc.queryForObject(explain, String.class, arguments)).get(0);
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("unpublished", unpublished);
            sample.put("candidate", candidate);
            sample.put("query", query);
            sample.put("repeat", repeat);
            sample.put("sql", sql);
            sample.put("plan", plan);
            samples.add(sample);
            emit("SAMPLE", sample);
        }
    }

    private void emit(String kind, Object value) {
        System.out.println("OUTBOX_INDEX_" + kind + " " + JSON.writeValueAsString(value));
    }
}
