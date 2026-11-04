package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.lang.management.ManagementFactory;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.support.JpaEntityInformationSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

abstract class AnomalyProbeSupport {
    @Autowired NotificationRepository repository;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired AnomalyProbeMeter meter;
    @Autowired ApplicationContext context;
    Map<String, Object> environment;
    private final Map<ch.qos.logback.classic.Logger, ch.qos.logback.classic.Level> originalLogLevels = new java.util.HashMap<>();

    @BeforeEach
    void clearRowsAndEnvironment() throws Exception {
        for (String category : List.of("org.hibernate.SQL", "org.hibernate.orm.jdbc", "org.hibernate.engine.jdbc.spi.SqlExceptionHelper")) {
            var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(category);
            originalLogLevels.put(logger, logger.getLevel());
            logger.setLevel(ch.qos.logback.classic.Level.OFF);
        }
        assertThat(context.getBeanNamesForType(NotificationRepository.class)).hasSize(1);
        assertThat(context.containsBean("operationsServiceApplication")).isFalse();
        assertThat(context.getBeansWithAnnotation(org.springframework.cloud.openfeign.EnableFeignClients.class)).isEmpty();
        assertThat(context.getBeanNamesForType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(context.getBeanNamesForType(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class)).isEmpty();
        jdbc.update("delete from operations.notification");
        var env = new LinkedHashMap<String, Object>();
        env.put("jdkVendor", System.getProperty("java.vendor"));
        env.put("jdkVersion", Runtime.version().toString());
        env.put("springDataJpa", version(NotificationRepository.class.getInterfaces()[0]));
        env.put("hibernate", org.hibernate.Version.getVersionString());
        env.put("cpuAvailable", Runtime.getRuntime().availableProcessors());
        env.put("cpuArchitecture", System.getProperty("os.arch"));
        env.put("execution", "same-thread in experiment class; concurrent host workloads unknown; no exclusive-host claim");
        env.put("heapMaxBytes", Runtime.getRuntime().maxMemory());
        env.put("gc", ManagementFactory.getGarbageCollectorMXBeans().stream().map(bean -> bean.getName()).toList());
        var factory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        env.put("factoryJdbcBatchSizeHint", factory.getSessionFactoryOptions().getJdbcBatchSize());
        env.put("batchInterpretation", "factory hint/session override do not prove batching; inspect jdbc_executeBatch and batchParameterRows");
        env.put("orderInserts", factory.getSessionFactoryOptions().isOrderInsertsEnabled());
        env.put("orderUpdates", factory.getSessionFactoryOptions().isOrderUpdatesEnabled());
        env.put("measurementScope", "input prebuilt/excluded; Entity/context/result allocations included; worker JDBC/thread allocation; repository then tx commit");
        env.put("heapLimit", "2ms sampled JVM heap lower bound, not retained heap/leak/whole-process peak; sampler overhead included");
        try (var connection = jdbc.getDataSource().getConnection()) {
            var meta = connection.getMetaData();
            env.put("databaseVersion", meta.getDatabaseProductVersion());
            env.put("driverVersion", meta.getDriverVersion());
            env.put("rewriteBatchedInserts", "not-applicable");
            if (meta.getDatabaseProductName().equals("PostgreSQL")) {
                // runtimeOnly 드라이버의 실효 속성만 읽는다. URL/인증 값은 출력하지 않는다.
                Class<?> baseType = Class.forName("org.postgresql.core.BaseConnection");
                var base = connection.unwrap(baseType);
                var executor = baseType.getMethod("getQueryExecutor").invoke(base);
                env.put("rewriteBatchedInserts", Class.forName("org.postgresql.core.QueryExecutor")
                    .getMethod("isReWriteBatchedInsertsEnabled").invoke(executor));
            }
        }
        environment = Map.copyOf(env);
    }

    @AfterEach
    void restoreLogging() {
        originalLogLevels.forEach(ch.qos.logback.classic.Logger::setLevel);
        originalLogLevels.clear();
    }

    AnomalyProbeStore store() {
        return new AnomalyProbeStore(repository, entityManager);
    }

    TransactionTemplate transaction() {
        var template = new TransactionTemplate(transactionManager);
        template.setTimeout(15);
        return template;
    }

    record Result(List<Notification> saved, Map<String, Object> explicit, Map<String, Object> total, Throwable failure) { }

    Result measure(String mode, int n, int iteration, Supplier<List<Notification>> action) {
        var explicit = new AtomicReference<Map<String, Object>>(Map.of());
        List<Notification> saved = List.of();
        Throwable failure = null;
        try (var window = meter.start(entityManagerFactory.unwrap(SessionFactory.class))) {
            try {
                saved = transaction().execute(status -> {
                    try {
                        return action.get();
                    } finally {
                        var repositoryMetrics = new LinkedHashMap<String, Object>(window.snapshot());
                        Integer override = entityManager.unwrap(org.hibernate.Session.class).getJdbcBatchSize();
                        repositoryMetrics.put("sessionJdbcBatchSizeOverride", override == null ? "unset/inherit-factory-hint" : override);
                        explicit.set(Map.copyOf(repositoryMetrics));
                    }
                });
            } catch (RuntimeException | AssertionError error) {
                failure = error;
            }
            var total = window.snapshot();
            var metrics = new LinkedHashMap<String, Object>();
            metrics.put("explicitRepository", explicit.get());
            metrics.put("transactionIncludingCommitOrRollback", total);
            if (failure != null) {
                metrics.put("failureClass", failure.getClass().getSimpleName());
                metrics.put("sqlState", sqlState(failure));
            }
            emit(mode, n, iteration, failure == null ? "COMMITTED" : "ROLLED_BACK", metrics);
            return new Result(saved, explicit.get(), total, failure);
        }
    }

    Result committed(AnomalyProbeStore.Mode mode, int n, int iteration) {
        long before = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        var input = AnomalyProbeStore.fixture(n); // 입력 생성 할당은 저장 계측 범위 밖이다.
        long after = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        emit(mode + "_INPUT_BUILD", n, iteration, "BUILT", Map.of("heapBeforeBytes", before, "heapAfterBytes", after,
            "heapDeltaBytes", after - before, "scope", "input only; before/after is not allocation, peak or retained heap"));
        var result = measure(mode.name(), n, iteration, () -> store().insert(mode, input));
        assertThat(result.failure()).as("normal commit gate").isNull();
        assertNewSql(result.explicit(), n);
        assertNewSql(result.total(), n);
        assertMapping(input, result.saved());
        assertThat(AnomalyProbeMeter.value(result.explicit(), "hibernateFlush"))
            .isEqualTo(mode == AnomalyProbeStore.Mode.BASELINE ? n : 1);
        return result;
    }

    void assertNewSql(Map<String, Object> counts, int n) {
        assertThat(AnomalyProbeMeter.value(counts, "selectAttemptRows")).isZero();
        assertThat(AnomalyProbeMeter.value(counts, "insertRows")).isEqualTo(n);
        assertThat(AnomalyProbeMeter.value(counts, "updateAttemptRows")).isZero();
        assertThat(AnomalyProbeMeter.value(counts, "otherAttemptRows")).isZero();
        assertThat(AnomalyProbeMeter.value(counts, "hibernateInsert")).isEqualTo(n);
        assertThat(AnomalyProbeMeter.value(counts, "hibernateUpdate")).isZero();
    }

    void assertMapping(List<Notification> input, List<Notification> output) {
        assertThat(output).extracting(Notification::id).containsExactlyElementsOf(input.stream().map(Notification::id).toList());
        assertThatThrownBy(() -> output.add(input.getFirst())).isInstanceOf(UnsupportedOperationException.class);
        var stored = transaction().execute(status -> repository.findAll()); // 계측 밖, 별도 트랜잭션으로 커밋 행 검증
        assertThat(stored).hasSize(input.size());
        var byId = stored.stream().collect(java.util.stream.Collectors.toMap(NotificationEntity::getId, value -> value));
        for (int i = 0; i < input.size(); i++) {
            var expected = input.get(i);
            var actual = byId.get(expected.id());
            assertThat(actual).isNotNull();
            assertThat(actual.getRecipientMemberId()).isEqualTo(expected.recipientMemberId());
            assertThat(actual.getSourceId()).isEqualTo(expected.sourceId());
            assertThat(actual.getSourceType()).isEqualTo(expected.sourceType());
            assertThat(actual.getKind()).isEqualTo(expected.kind());
            assertThat(actual.getSeverity()).isEqualTo(expected.severity());
            assertThat(actual.getTitle()).isEqualTo(expected.title());
            assertThat(actual.getLinkPath()).isEqualTo(expected.linkPath());
            assertThat(actual.getReadAt()).isNull();
            assertThat(actual.getCreatedAt()).isNotNull();
            assertThat(actual.getUpdatedAt()).isNotNull();
            assertThat(actual.getVersion()).isZero();
            assertThat(output.get(i).version()).isZero();
            assertThat(output.get(i).createdAt()).isNotNull();
        }
    }

    @Test
    void actualNewDetectionAndDetachedMergeControl() {
        var fresh = AnomalyProbeStore.entity(AnomalyProbeStore.fixture(1).getFirst());
        var information = JpaEntityInformationSupport.getEntityInformation(NotificationEntity.class, entityManager);
        assertThat(information.isNew(fresh)).isTrue();
        var inserted = measure("IS_NEW_PERSIST", 1, 1, () -> {
            var saved = repository.saveAndFlush(fresh);
            assertThat(saved).isSameAs(fresh);
            return List.of(AnomalyProbeStore.domain(saved));
        });
        assertThat(inserted.failure()).isNull();
        assertNewSql(inserted.total(), 1);
        assertThat(fresh.getVersion()).isNotNull();
        // PostgreSQL 마이크로초 정밀도로 재조회한 감사 값을 대조한다.
        var detached = transaction().execute(status -> repository.findById(fresh.getId()).orElseThrow());
        var created = detached.getCreatedAt();
        var updated = detached.getUpdatedAt();
        assertThat(information.isNew(detached)).isFalse();
        var merged = measure("IS_NEW_FALSE_MERGE", 1, 1, () -> {
            var managed = repository.saveAndFlush(detached);
            assertThat(managed).isNotSameAs(detached);
            return List.of(AnomalyProbeStore.domain(managed));
        });
        assertThat(merged.failure()).isNull();
        assertThat(AnomalyProbeMeter.value(merged.total(), "selectRows")).isPositive();
        assertThat(AnomalyProbeMeter.value(merged.total(), "insertRows")).isZero();
        assertThat(AnomalyProbeMeter.value(merged.total(), "updateRows")).isZero();
        var after = transaction().execute(status -> repository.findById(fresh.getId()).orElseThrow());
        assertThat(after.getCreatedAt()).isEqualTo(created);
        assertThat(after.getUpdatedAt()).isEqualTo(updated);
        assertThat(after.getVersion()).isEqualTo(detached.getVersion());
        emit("ENTITY_INFORMATION", 1, 1, "VERIFIED", Map.of("implementation", information.getClass().getName(), "new", true, "detached", false));
    }

    void emit(String mode, int n, int iteration, String outcome, Map<String, ?> metrics) {
        System.out.println("ANOMALY_STORAGE_PROBE " + JsonMapper.builder().build().writeValueAsString(Map.of(
            "field", "notification", "mode", mode, "n", n, "iteration", iteration, "outcome", outcome, "metrics", metrics, "env", environment)));
    }

    static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState() == null ? "unknown" : sql.getSQLState();
            }
        }
        return "none";
    }

    private static String version(Class<?> type) {
        String value = type.getPackage().getImplementationVersion();
        if (value != null) {
            return value;
        }
        String location = type.getProtectionDomain().getCodeSource().getLocation().getPath();
        return location.substring(location.lastIndexOf('/') + 1);
    }
}
