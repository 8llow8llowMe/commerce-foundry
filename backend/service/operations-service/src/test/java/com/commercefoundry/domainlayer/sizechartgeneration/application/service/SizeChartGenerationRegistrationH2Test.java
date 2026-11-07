package com.commercefoundry.domainlayer.sizechartgeneration.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.commercefoundry.domainlayer.job.adapter.out.event.JobDispatchEventAdapter;
import com.commercefoundry.domainlayer.job.adapter.out.persistence.JobRepositoryAdapter;
import com.commercefoundry.domainlayer.job.adapter.out.persistence.entity.JobEntity;
import com.commercefoundry.domainlayer.job.adapter.out.persistence.repository.JobRepository;
import com.commercefoundry.domainlayer.job.application.mapper.JobMapper;
import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.application.service.processor.JobContextReadProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobHandlerProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRegistrationProcessor;
import com.commercefoundry.domainlayer.job.domain.model.JobHandler;
import com.commercefoundry.domainlayer.job.support.JobFixtures;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.dto.response.SizeChartGenerationRegistrationResponse;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.presenter.SizeChartGenerationInternalPresenter;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.persistence.SizeChartGenerationReceiptRepositoryAdapter;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.persistence.entity.SizeChartGenerationReceiptEntity;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.persistence.repository.SizeChartGenerationReceiptRepository;
import com.commercefoundry.domainlayer.sizechartgeneration.application.command.SizeChartGenerationRegisterCommand;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationErrorCode;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationException;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationReceiptPort;
import com.commercefoundry.domainlayer.sizechartgeneration.application.service.processor.SizeChartGenerationReceiptProcessor;
import com.commercefoundry.domainlayer.sizechartgeneration.application.service.processor.SizeChartGenerationResultProcessor;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationReceipt;
import com.commercefoundry.global.messaging.JpaOutboxStore;
import com.commercefoundry.global.messaging.OutboxEntity;
import com.commercefoundry.global.messaging.OutboxRepository;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.publish.EventPublisher;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Real job + outbox + receipt persistence and transaction proxies; no worker, AI, Kafka, or network. */
@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:size-chart-registration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;"
        + "INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.default_schema=operations",
    "operations.size-chart-generation.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SizeChartGenerationRegistrationH2Test.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SizeChartGenerationRegistrationH2Test {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    @Autowired
    private SizeChartGenerationInternalFacade facade;
    @Autowired
    private JobRepository jobs;
    @Autowired
    private JobRepositoryPort jobPort;
    @Autowired
    private OutboxRepository outbox;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager entityManager;
    @MockitoSpyBean
    private SizeChartGenerationReceiptPort receipts;
    @Autowired
    private JobRegistrationProcessor registration;
    @Autowired
    private JobContextReadProcessor jobReader;
    @Autowired
    private SizeChartGenerationReceiptProcessor receiptProcessor;
    @Autowired
    private SizeChartGenerationResultProcessor resultProcessor;

    @BeforeEach
    void clean() {
        reset(receipts);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery("delete from SizeChartGenerationReceiptEntity").executeUpdate();
            outbox.deleteAllInBatch();
            jobs.deleteAllInBatch();
        });
    }

    private SizeChartGenerationRegisterCommand command(long draftId, String hash) {
        return new SizeChartGenerationRegisterCommand(draftId, hash, 7, NOW.plusSeconds(60));
    }

    @Test
    void duplicateSameHashReturnsSameJobAndOneOutbox() {
        var command = command(10, HASH);
        var first = facade.register(command);
        assertThat(facade.register(command)).isEqualTo(first);
        assertThat(first.inputHash()).isEqualTo(HASH);
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
        var receipt = new TransactionTemplate(transactionManager).execute(status -> receipts.findByDraftId(10).orElseThrow());
        assertThat(receipt).isEqualTo(new SizeChartGenerationReceipt(10, Long.parseLong(first.jobId()), HASH, 7, command.expiresAt()));
        var job = jobs.findById(receipt.jobId()).orElseThrow();
        assertThat(job.getType()).isEqualTo("SIZE_CHART_GENERATION");
        assertThat(job.getDedupKey()).isEqualTo("size-chart:10");
        assertThat(job.getPayloadJson()).isEqualTo("{\"draftId\":10,\"inputHash\":\"" + HASH + "\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-07T00:01:00.123456789Z", "2026-11-07T00:01:00.999999999Z"})
    void nanosRoundTripReplaysOriginalAndReloadedExpiry(String expiry) {
        Instant originalExpiry = Instant.parse(expiry);
        var original = new SizeChartGenerationRegisterCommand(10, HASH, 7, originalExpiry);
        var first = facade.register(original);
        var reloaded = new TransactionTemplate(transactionManager).execute(status -> receipts.findByDraftId(10).orElseThrow());
        assertThat(original.expiresAt()).isEqualTo(originalExpiry.truncatedTo(ChronoUnit.MICROS));
        assertThat(reloaded.expiresAt()).isEqualTo(original.expiresAt());
        assertThat(facade.register(original)).isEqualTo(first);
        assertThat(facade.register(new SizeChartGenerationRegisterCommand(10, HASH, 7, reloaded.expiresAt()))).isEqualTo(first);
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void originalAndActuallyReloadedRequestsReplayConcurrentlyWithoutExtraJobOrOutbox() throws Exception {
        var original = new SizeChartGenerationRegisterCommand(10, HASH, 7, NOW.plusSeconds(60).plusNanos(123456789));
        var first = facade.register(original);
        var receipt = new TransactionTemplate(transactionManager).execute(status -> receipts.findByDraftId(10).orElseThrow());
        var reloaded = new SizeChartGenerationRegisterCommand(receipt.draftId(), receipt.inputHash(), receipt.requestedByMemberId(), receipt.expiresAt());
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var originalReplay = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return facade.register(original);
            });
            var reloadedReplay = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return facade.register(reloaded);
            });
            assertThat(originalReplay.get(20, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(reloadedReplay.get(20, TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void distinctMicrosecondExpiryStillConflicts() {
        var command = new SizeChartGenerationRegisterCommand(10, HASH, 7, NOW.plusSeconds(60).plusNanos(123456789));
        facade.register(command);
        var different = new SizeChartGenerationRegisterCommand(10, HASH, 7, command.expiresAt().plus(1, ChronoUnit.MICROS));
        assertThatThrownBy(() -> facade.register(different)).isInstanceOfSatisfying(SizeChartGenerationException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(SizeChartGenerationErrorCode.INPUT_CONFLICT));
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void conflictingHashReturns409AndLeavesOriginalUntouched() {
        var first = facade.register(command(10, HASH));
        assertThatThrownBy(() -> facade.register(command(10, "b".repeat(64))))
            .isInstanceOfSatisfying(SizeChartGenerationException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(SizeChartGenerationErrorCode.INPUT_CONFLICT));
        assertThat(facade.register(command(10, HASH))).isEqualTo(first);
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void completedReplayRemainsStableEvenAfterExpiryAndWhenRegistrationDisabled() {
        var first = facade.register(command(10, HASH));
        long jobId = Long.parseLong(first.jobId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var job = jobPort.findById(jobId).orElseThrow();
            var running = jobPort.update(job.claim("test-worker", NOW, Duration.ofMinutes(5)));
            jobPort.update(running.succeed("{\"rows\":[]}", NOW.plusSeconds(1)));
        });
        var expiredClock = Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC);
        var expiredReceiptProcessor = new SizeChartGenerationReceiptProcessor(receipts, resultProcessor, expiredClock);
        var replayFacade = new SizeChartGenerationInternalFacade(registration, jobReader, expiredReceiptProcessor, resultProcessor,
            new SizeChartGenerationInternalPresenter(), transactionManager, false);
        assertThat(replayFacade.register(command(10, HASH))).isEqualTo(first);
        assertThat(facade.result(jobId).status()).isEqualTo("SUCCEEDED");
        assertThat(facade.result(jobId).inputHash()).isEqualTo(HASH);
        assertThat(facade.result(jobId).resultJson()).isEqualTo("{\"rows\":[]}");
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void changedActorOrExpiryCannotReplayEvenWithSameHash() {
        var first = facade.register(command(10, HASH));
        var changedActor = new SizeChartGenerationRegisterCommand(10, HASH, 8, NOW.plusSeconds(60));
        var changedExpiry = new SizeChartGenerationRegisterCommand(10, HASH, 7, NOW.plusSeconds(61));
        for (var changed : List.of(changedActor, changedExpiry)) {
            assertThatThrownBy(() -> facade.register(changed)).isInstanceOfSatisfying(SizeChartGenerationException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(SizeChartGenerationErrorCode.INPUT_CONFLICT));
        }
        assertThat(facade.register(command(10, HASH))).isEqualTo(first);
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void expiredFirstRegistrationCreatesNothing() {
        var expired = new SizeChartGenerationRegisterCommand(10, HASH, 7, NOW);
        assertThatThrownBy(() -> facade.register(expired)).isInstanceOfSatisfying(SizeChartGenerationException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(SizeChartGenerationErrorCode.EXPIRED));
        assertThat(jobs.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void disabledRegistrationCreatesNothing() {
        assertThatThrownBy(() -> disabled().register(command(10, HASH))).isInstanceOfSatisfying(SizeChartGenerationException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(SizeChartGenerationErrorCode.UNAVAILABLE));
        assertThat(jobs.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    private SizeChartGenerationInternalFacade disabled() {
        return new SizeChartGenerationInternalFacade(registration, jobReader, receiptProcessor, resultProcessor,
            new SizeChartGenerationInternalPresenter(), transactionManager, false);
    }

    @Test
    void receiptFailureRollsBackFlushedJobAndOutboxThenAllowsRetry() {
        AtomicBoolean observedAllWrites = new AtomicBoolean();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(jobs.count()).isEqualTo(1);
            assertThat(outbox.count()).isEqualTo(1);
            observedAllWrites.set(true);
            throw new DataIntegrityViolationException("synthetic receipt failure");
        }).when(receipts).insert(any());
        assertThatThrownBy(() -> facade.register(command(10, HASH))).isInstanceOfSatisfying(SizeChartGenerationException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(SizeChartGenerationErrorCode.UNAVAILABLE));
        assertThat(observedAllWrites).isTrue();
        assertThat(jobs.count()).isZero();
        assertThat(outbox.count()).isZero();
        var missing = new TransactionTemplate(transactionManager).execute(status -> receipts.findByDraftId(10));
        assertThat(missing).isEmpty();
        reset(receipts);
        assertThat(facade.register(command(10, HASH)).jobId()).isNotBlank();
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void simultaneousFirstRegistrationsReplayWinnerInFreshTransaction() throws Exception {
        race(command(10, HASH), command(10, HASH), false);
    }

    @Test
    void simultaneousDifferentHashesLeaveOneJobAndOne409() throws Exception {
        race(command(10, HASH), command(10, "b".repeat(64)), true);
    }

    @Test
    void simultaneousOriginalAndPersistencePrecisionRequestsReplaySameWinner() throws Exception {
        Instant expiry = NOW.plusSeconds(60).plusNanos(123456789);
        race(new SizeChartGenerationRegisterCommand(10, HASH, 7, expiry),
            new SizeChartGenerationRegisterCommand(10, HASH, 7, expiry.truncatedTo(ChronoUnit.MICROS)), false);
    }

    private void race(SizeChartGenerationRegisterCommand original, SizeChartGenerationRegisterCommand reloaded, boolean conflict) throws Exception {
        CyclicBarrier absentInBothTransactions = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (reads.getAndIncrement() < 2) {
                absentInBothTransactions.await(10, TimeUnit.SECONDS);
            }
            return result;
        }).when(receipts).findByDraftId(anyLong());
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Object>> calls = List.of(() -> attempt(original), () -> attempt(reloaded));
            var tasks = calls.stream().map(executor::submit).toList();
            Object first = tasks.get(0).get(20, TimeUnit.SECONDS);
            Object second = tasks.get(1).get(20, TimeUnit.SECONDS);
            if (conflict) {
                assertThat(List.of(first, second)).filteredOn(value -> value == SizeChartGenerationErrorCode.INPUT_CONFLICT).hasSize(1);
            } else {
                assertThat(first).isInstanceOf(
                    SizeChartGenerationRegistrationResponse.class);
                assertThat(first).isEqualTo(second);
            }
        }
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(reads.get()).isGreaterThanOrEqualTo(3);
    }

    private Object attempt(SizeChartGenerationRegisterCommand command) {
        try {
            return facade.register(command);
        } catch (SizeChartGenerationException exception) {
            return exception.getErrorCode();
        }
    }

    @Test
    void receiptIsInsertOnlyAndJobIdUnique() {
        facade.register(command(10, HASH));
        var tx = new TransactionTemplate(transactionManager);
        var receipt = tx.execute(status -> receipts.findByDraftId(10).orElseThrow());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> receipts.insert(receipt))).isInstanceOf(DataIntegrityViolationException.class);
        var reusedJob = new SizeChartGenerationReceipt(11, receipt.jobId(), HASH, 7, receipt.expiresAt());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> receipts.insert(reusedJob))).isInstanceOf(DataIntegrityViolationException.class);
        var missing = tx.execute(status -> receipts.findByDraftId(11));
        assertThat(missing).isEmpty();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EntityScan(basePackageClasses = {JobEntity.class, OutboxEntity.class, SizeChartGenerationReceiptEntity.class})
    @EnableJpaRepositories(basePackageClasses = {JobRepository.class, OutboxRepository.class, SizeChartGenerationReceiptRepository.class})
    @Import({JobRegistrationProcessor.class, JobRepositoryAdapter.class, JobDispatchEventAdapter.class, JpaOutboxStore.class,
        SizeChartGenerationReceiptRepositoryAdapter.class, JobContextReadProcessor.class, SizeChartGenerationReceiptProcessor.class,
        SizeChartGenerationResultProcessor.class, SizeChartGenerationInternalFacade.class, SizeChartGenerationInternalPresenter.class})
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        SnowflakeIdGenerator ids() {
            return new SnowflakeIdGenerator(1, 1);
        }

        @Bean
        JobMapper jobMapper() {
            return Mappers.getMapper(JobMapper.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        JobHandler sizeChartTestHandler() {
            return JobFixtures.handler("SIZE_CHART_GENERATION");
        }

        @Bean
        JobHandlerProcessor handlers(JobHandler handler) {
            return new JobHandlerProcessor(JobFixtures.handlers(handler), JobFixtures.properties());
        }

        @Bean
        EventPublisher events(JpaOutboxStore store, Clock clock) {
            return new EventPublisher(() -> store, new EventEnvelopeCodec(65536), "operations-service", clock);
        }
    }
}
