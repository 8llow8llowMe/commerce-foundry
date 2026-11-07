package com.commercefoundry.domainlayer.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.notification.adapter.in.scheduler.PaymentAnomalyAlertScheduler;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.NotificationBatchInsertAdapter;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.PaymentAnomalyAlertStateAdapter;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.PaymentAnomalyAlertStateRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.stream.NotificationPushAdapter;
import com.commercefoundry.domainlayer.notification.adapter.out.stream.PaymentAnomalyAlertPushAdapter;
import com.commercefoundry.domainlayer.notification.application.model.NotificationPush;
import com.commercefoundry.domainlayer.notification.application.model.NotificationStream;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRecipientPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyAlertLockPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyObservationPort;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentAnomalyAlertPushProcessor;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentAnomalyAlertStateProcessor;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentAnomalyObservationProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyObservation;
import com.commercefoundry.global.config.PaymentAnomalyAlertConfig;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.stream.LongStream;
import org.aopalliance.intercept.MethodInterceptor;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.aop.framework.Advised;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** One bounded experiment, no retries or heap increase. Diagnostics are not latency SLAs or retained-heap proofs. */
@SpringJUnitConfig(PaymentAnomalySaturationPostgresTest.Config.class)
@Testcontainers
@EnabledWithDockerOrOnCi
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=operations",
    "spring.jpa.properties.hibernate.generate_statistics=true", "spring.data.jpa.repositories.enabled=false",
    "spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.data-source-properties.logServerErrorDetail=false",
    "operations.notifications.payment-anomaly-alert.enabled=true"
})
class PaymentAnomalySaturationPostgresTest {
    private static final int BATCH_SIZE = 5000;
    private static final int BATCHES = 17;
    private static final int OCCUPIED = BATCH_SIZE * BATCHES;
    private static final List<Long> RECIPIENTS = LongStream.rangeClosed(1, 1000).boxed().toList();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired PaymentAnomalyAlertScheduler collector;
    @Autowired PaymentAnomalyAlertStateProcessor storage;
    @Autowired PaymentAnomalyAlertPushAdapter delivery;
    @Autowired NotificationPushAdapter streams;
    @Autowired EntityManagerFactory factory;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("paymentAnomalyAlertTaskScheduler") ThreadPoolTaskScheduler collectionScheduler;

    @Test
    void realPostgresCommitSurvivesRejectionWhileDistinct85000NotificationsOccupyDelivery() throws Exception {
        var hotspot = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        assertThat(hotspot).as("requires a measurable configured JVM heap").isNotNull();
        assertThat(Long.parseLong(hotspot.getVMOption("MaxHeapSize").getValue())).as("existing test task -Xmx768m").isEqualTo(768L * 1024 * 1024);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        jdbc.update("delete from operations.notification");
        jdbc.update("delete from operations.payment_anomaly_alert_state");
        var safeEnvironment = environment();

        var executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(delivery, "executor");
        assertThat(executor).isNotNull();
        var originalRejection = executor.getRejectedExecutionHandler();
        var rejects = new AtomicLong();
        var rejectedAt = new AtomicLong();
        var storageNanos = new AtomicLong();
        var storageReturnedAt = new AtomicLong();
        var storageProxy = (Advised) storage;
        MethodInterceptor storageTiming = invocation -> {
            if (!invocation.getMethod().getName().equals("accept")) { return invocation.proceed(); }
            long before = System.nanoTime();
            try { return invocation.proceed(); }
            finally {
                long after = System.nanoTime();
                storageNanos.set(after - before);
                storageReturnedAt.set(after);
            }
        };
        // Outermost advice surrounds the production transaction interceptor, including its actual commit.
        storageProxy.addAdvice(0, storageTiming);
        // Count the actual executor rejection, retaining AbortPolicy and the production adapter's catch path.
        executor.setRejectedExecutionHandler((task, pool) -> {
            rejects.incrementAndGet();
            rejectedAt.set(System.nanoTime());
            originalRejection.rejectedExecution(task, pool);
        });
        var gate = new Gate();
        RECIPIENTS.forEach(id -> streams.register(id, gate));
        var metrics = new LinkedHashMap<String, Object>();
        metrics.put("environment", safeEnvironment);
        metrics.put("fixtureNotifications", OCCUPIED);
        metrics.put("fixtureBatches", BATCHES);
        metrics.put("measurementScope", "fixture build, queue occupancy, real collector create/flush/commit/rejection, release/drain; no HTTP transport");
        metrics.put("heapScope", "2ms sampled JVM heap lower bound, includes instrumentation; not retained heap or process peak");
        metrics.put("referenceScope", "85000 distinct fixture records, only adapter retains batches; BitSet/counters retain no notification objects");
        var outcome = "FAILED";
        long started = System.nanoTime();
        try (var measurement = new Measurement()) {
            try {
                enqueueFixture(0);
                assertThat(gate.entered.await(10, TimeUnit.SECONDS)).as("real SSE callback reached cooperative gate").isTrue();
                for (int batch = 1; batch < BATCHES; batch++) { enqueueFixture(batch); }
                assertFull(executor, rejects);
                metrics.put("occupied", occupancy(executor, rejects));
                metrics.put("fixtureBuildAndEnqueueNanos", System.nanoTime() - started);
                metrics.put("heapWhileFullBytes", heap());
                measurement.workerBaseline(gate.workerId.get());

                // The production collector scheduler must progress while the push worker is blocked.
                var progress = new CountDownLatch(1);
                collectionScheduler.schedule(progress::countDown, Instant.now());
                assertThat(progress.await(5, TimeUnit.SECONDS)).isTrue();
                metrics.put("independentProbeSchedulerProgress", true);

                var statistics = factory.unwrap(SessionFactory.class).getStatistics();
                statistics.clear();
                long allocationBefore = measurement.ownerAllocated();
                long collectionStarted = System.nanoTime();
                collector.observe(); // NOT_SUPPORTED facade -> actual transactional StateProcessor -> real PG commit -> saturated adapter.
                metrics.put("collectorCreateFlushCommitAndSubmitNanos", System.nanoTime() - collectionStarted);
                metrics.put("storageProxyIncludingCommitNanos", storageNanos.get());
                metrics.put("postCommitReturnToRejectionNanos", rejectedAt.get() - storageReturnedAt.get());
                metrics.put("collectorThreadAllocatedBytes", delta(allocationBefore, measurement.ownerAllocated()));
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(statistics.getEntityInsertCount()).as("5000 notifications and five state rows").isEqualTo(5005);
                assertThat(statistics.getFlushCount()).isPositive();
                metrics.put("hibernateInsertCount", statistics.getEntityInsertCount());
                metrics.put("hibernateFlushCount", statistics.getFlushCount());
                assertThat(executor.getActiveCount()).isEqualTo(1);
                assertThat(executor.getQueue()).hasSize(16);
                assertThat(rejects).hasValue(1);
                assertThat(storageNanos.get()).isPositive();
                assertThat(rejectedAt.get()).isGreaterThanOrEqualTo(storageReturnedAt.get());
                assertThat(executor.getCompletedTaskCount()).isZero();
                assertThat(gate.release.getCount()).isEqualTo(1);
                metrics.put("afterCommitRejection", occupancy(executor, rejects));

                // A separate, autocommit JDBC borrow reads actual committed rows; no test transaction surrounds the proxy.
                assertCommitted();
                metrics.put("committedNotificationRows", BATCH_SIZE);
                metrics.put("committedStateRows", 5);
                long drainedAt = System.nanoTime();
                gate.release.countDown();
                assertThat(gate.drained.await(30, TimeUnit.SECONDS)).as("all accepted notifications drained").isTrue();
                executor.shutdown();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("push worker joined").isTrue();
                assertThat(gate.failure.get()).isNull();
                assertThat(gate.seen.cardinality()).as("each of 85000 unique fixture IDs delivered exactly once").isEqualTo(OCCUPIED);
                assertThat(gate.sent).hasValue(OCCUPIED);
                assertThat(gate.unexpected).hasValue(0);
                assertThat(executor.getCompletedTaskCount()).isEqualTo(BATCHES);
                assertThat(executor.getQueue()).isEmpty();
                assertCommitted();
                metrics.put("drainAndJoinNanos", System.nanoTime() - drainedAt);
                metrics.put("drained", occupancy(executor, rejects));
                metrics.put("distinctDeliveredIds", gate.seen.cardinality());
                metrics.put("workerJoined", executor.isTerminated());
                outcome = "COMMITTED_AND_REJECTED";
            } finally {
                // Release before every wait/close, including failed assertions and transaction failures; never retry the experiment.
                gate.release.countDown();
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                        delivery.close();
                        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("cooperative push cleanup joined").isTrue();
                    }
                } finally {
                    RECIPIENTS.forEach(id -> streams.unregister(id, gate));
                    executor.setRejectedExecutionHandler(originalRejection);
                    storageProxy.removeAdvice(storageTiming);
                    metrics.put("cleanupWorkerJoined", executor.isTerminated());
                    metrics.put("measurement", measurement.snapshot(gate.allocatedAtLastSend.get()));
                    metrics.put("elapsedNanos", System.nanoTime() - started);
                    // Explicit numeric/version allowlist only: no SQL, URLs, credentials, heap dump, arguments or messages.
                    System.out.println("ANOMALY_SATURATION_PROBE " + JsonMapper.builder().build().writeValueAsString(
                        Map.of("outcome", outcome, "metrics", metrics)));
                }
            }
        }
    }

    private void enqueueFixture(int batch) {
        var records = new ArrayList<Notification>(BATCH_SIZE);
        for (int offset = 1; offset <= BATCH_SIZE; offset++) {
            long id = (long) batch * BATCH_SIZE + offset;
            // New record and payload strings per item; disjoint ID intervals; no repeated list or Notification instance.
            records.add(new Notification(id, (offset - 1) % 1000 + 1, NotificationKind.ANOMALY, NotificationSeverity.WARNING,
                "합성 결제 이상 후보 " + id, "/admin/payments?probe=" + id, "SATURATION_" + id, id,
                null, Instant.ofEpochSecond(1_800_000_000L, id * 1000), 0L));
        }
        delivery.enqueue(List.copyOf(records));
    }

    private void assertCommitted() {
        assertThat(jdbc.queryForObject("select count(*) from operations.notification", Long.class)).isEqualTo((long) BATCH_SIZE);
        assertThat(jdbc.queryForObject("select count(distinct id) from operations.notification", Long.class)).isEqualTo((long) BATCH_SIZE);
        assertThat(jdbc.queryForObject("select count(distinct recipient_member_id) from operations.notification", Long.class)).isEqualTo(1000L);
        assertThat(jdbc.queryForObject("select count(*) from operations.notification where source_type = 'PAYMENT_ANOMALY_ALERT' "
            + "and version = 0 and created_at is not null and updated_at is not null", Long.class)).isEqualTo((long) BATCH_SIZE);
        assertThat(jdbc.queryForObject("select count(*) from operations.payment_anomaly_alert_state where status = 'ACTIVE'", Long.class)).isEqualTo(5L);
    }

    private static void assertFull(ThreadPoolExecutor executor, AtomicLong rejects) {
        assertThat(executor.getActiveCount()).isEqualTo(1);
        assertThat(executor.getQueue()).hasSize(16);
        assertThat(executor.getQueue().remainingCapacity()).isZero();
        assertThat(executor.getTaskCount()).isEqualTo(BATCHES);
        assertThat(executor.getCompletedTaskCount()).isZero();
        assertThat(rejects).hasValue(0);
    }

    private static Map<String, Object> occupancy(ThreadPoolExecutor pool, AtomicLong rejects) {
        return Map.of("active", pool.getActiveCount(), "queuedBatches", pool.getQueue().size(), "rejectedBatches", rejects.get(),
            "completedBatches", pool.getCompletedTaskCount(), "taskCount", pool.getTaskCount(),
            "notificationReferenceSlots", (pool.getActiveCount() + pool.getQueue().size()) * BATCH_SIZE);
    }

    private Map<String, Object> environment() throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("jdkVendor", System.getProperty("java.vendor"));
        result.put("jdkVersion", Runtime.version().toString());
        result.put("hibernate", org.hibernate.Version.getVersionString());
        result.put("cpuAvailable", Runtime.getRuntime().availableProcessors());
        result.put("cpuArchitecture", System.getProperty("os.arch"));
        result.put("configuredHeapBytes", 768L * 1024 * 1024);
        result.put("usableHeapMaxBytes", Runtime.getRuntime().maxMemory());
        result.put("gc", ManagementFactory.getGarbageCollectorMXBeans().stream().map(bean -> bean.getName()).toList());
        result.put("pgImage", POSTGRES.getDockerImageName());
        result.put("pgImageId", POSTGRES.getContainerInfo().getImageId());
        result.put("hostWorkload", "unknown; no exclusive-host or universal leak-free claim");
        try (var connection = jdbc.getDataSource().getConnection()) {
            var metadata = connection.getMetaData();
            result.put("databaseVersion", metadata.getDatabaseProductVersion());
            result.put("driverVersion", metadata.getDriverVersion());
        }
        return result;
    }

    private static long heap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
    private static long delta(long before, long after) { return before < 0 || after < 0 ? -1 : after - before; }

    private static final class Gate implements NotificationStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch drained = new CountDownLatch(OCCUPIED);
        private final AtomicLong workerId = new AtomicLong();
        private final AtomicLong sent = new AtomicLong();
        private final AtomicLong unexpected = new AtomicLong();
        private final AtomicLong allocatedAtLastSend = new AtomicLong(-1);
        private final AtomicReference<String> failure = new AtomicReference<>();
        private final BitSet seen = new BitSet(OCCUPIED);

        @Override
        public void send(NotificationPush message) throws IOException {
            if (workerId.compareAndSet(0, Thread.currentThread().threadId())) {
                entered.countDown();
                try {
                    if (!release.await(45, TimeUnit.SECONDS)) {
                        failure.compareAndSet(null, "gate_timeout");
                        throw new IOException("cooperative gate timeout");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    failure.compareAndSet(null, "gate_interrupted");
                    throw new IOException("cooperative gate interrupted");
                }
            }
            long id = Long.parseLong(message.notificationId());
            if (id < 1 || id > OCCUPIED) {
                unexpected.incrementAndGet();
                return;
            }
            int index = (int) id - 1;
            if (seen.get(index)) { failure.compareAndSet(null, "duplicate_fixture_id"); }
            seen.set(index);
            sent.incrementAndGet();
            if (drained.getCount() == 1 && ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled()) {
                // Read before worker termination; ThreadMXBean returns -1 for terminated threads.
                allocatedAtLastSend.set(bean.getThreadAllocatedBytes(Thread.currentThread().threadId()));
            }
            drained.countDown();
        }
    }

    private static final class Measurement implements AutoCloseable {
        private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(task -> new Thread(task, "anomaly-heap-probe"));
        private final long ownerId = Thread.currentThread().threadId();
        private final com.sun.management.ThreadMXBean allocations = allocationBean();
        private final long ownerStart = ownerAllocated();
        private final long heapStart = heap();
        private final AtomicLong heapPeak = new AtomicLong(heapStart);
        private final AtomicLong samples = new AtomicLong();
        private final long gcCountStart = gcCount();
        private final long gcMillisStart = gcMillis();
        private long workerId;
        private long workerStart = -1;

        Measurement() {
            sampler.scheduleAtFixedRate(() -> {
                heapPeak.accumulateAndGet(heap(), Math::max);
                samples.incrementAndGet();
            }, 0, 2, TimeUnit.MILLISECONDS);
        }

        void workerBaseline(long id) {
            workerId = id;
            workerStart = allocated(id);
        }

        long ownerAllocated() { return allocated(ownerId); }
        private long allocated(long id) { return allocations == null || id == 0 ? -1 : allocations.getThreadAllocatedBytes(id); }

        Map<String, Object> snapshot(long allocatedAtLastSend) {
            return Map.of("heapStartBytes", heapStart, "heapEndBytes", heap(), "sampledHeapPeakBytes", heapPeak.get(), "heapSamples", samples.get(),
                "samplePeriodMillis", 2, "ownerThreadAllocatedBytes", delta(ownerStart, ownerAllocated()),
                "workerAllocatedBytesGateToLastSend", delta(workerStart, allocatedAtLastSend),
                "gcCountDelta", delta(gcCountStart, gcCount()), "gcMillisDelta", delta(gcMillisStart, gcMillis()));
        }

        @Override
        public void close() throws InterruptedException {
            sampler.shutdownNow();
            assertThat(sampler.awaitTermination(5, TimeUnit.SECONDS)).as("heap sampler joined").isTrue();
        }

        private static com.sun.management.ThreadMXBean allocationBean() {
            if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported()) {
                if (!bean.isThreadAllocatedMemoryEnabled()) { bean.setThreadAllocatedMemoryEnabled(true); }
                return bean;
            }
            return null;
        }

        private static long gcCount() {
            var values = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> bean.getCollectionCount()).toArray();
            return java.util.Arrays.stream(values).anyMatch(value -> value < 0) ? -1 : java.util.Arrays.stream(values).sum();
        }

        private static long gcMillis() {
            var values = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> bean.getCollectionTime()).toArray();
            return java.util.Arrays.stream(values).anyMatch(value -> value < 0) ? -1 : java.util.Arrays.stream(values).sum();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class, TransactionAutoConfiguration.class})
    @EnableJpaAuditing
    @EnableTransactionManagement
    @EntityScan(basePackageClasses = NotificationEntity.class)
    @Import({PaymentAnomalyAlertConfig.class, PaymentAnomalyAlertStateAdapter.class, NotificationBatchInsertAdapter.class, PaymentAnomalyAlertStateProcessor.class,
        PaymentAnomalyAlertPushAdapter.class, NotificationPushAdapter.class, PaymentAnomalyAlertPushProcessor.class,
        PaymentAnomalyObservationProcessor.class, PaymentAnomalyAlertSchedulerFacade.class, PaymentAnomalyAlertScheduler.class})
    static class Config {
        @Bean
        NotificationRepository notifications(EntityManagerFactory factory) {
            return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory)).getRepository(NotificationRepository.class);
        }

        @Bean
        PaymentAnomalyAlertStateRepository states(EntityManagerFactory factory) {
            return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory)).getRepository(PaymentAnomalyAlertStateRepository.class);
        }

        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean SnowflakeIdGenerator ids() { return new SnowflakeIdGenerator(3, 8); }
        @Bean(name = "paymentAnomalyTicker", defaultCandidate = false) LongSupplier ticker() { return System::nanoTime; }
        @Bean NotificationRecipientPort recipients() { return () -> RECIPIENTS; }
        @Bean PaymentAnomalyAlertLockPort lock() { return Runnable::run; }
        @Bean
        PaymentAnomalyObservationPort source(Clock clock) {
            return () -> {
                var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                var empty = new PaymentAnomalyObservation.Fact(0, null);
                var active = new PaymentAnomalyObservation.Fact(1, now.minusSeconds(3600));
                return new PaymentAnomalyObservation(now, active, empty, active, active, active, active);
            };
        }
    }
}
