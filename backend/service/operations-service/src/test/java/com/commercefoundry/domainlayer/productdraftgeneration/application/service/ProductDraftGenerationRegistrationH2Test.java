package com.commercefoundry.domainlayer.productdraftgeneration.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.dto.response.ProductDraftGenerationRegistrationResponse;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.presenter.ProductDraftGenerationInternalPresenter;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.persistence.ProductDraftGenerationReceiptRepositoryAdapter;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.persistence.entity.ProductDraftGenerationReceiptEntity;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.persistence.repository.ProductDraftGenerationReceiptRepository;
import com.commercefoundry.domainlayer.productdraftgeneration.application.command.ProductDraftGenerationRegisterCommand;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationErrorCode;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationException;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.ProductDraftGenerationReceiptPort;
import com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor.ProductDraftGenerationJobProcessor;
import com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor.ProductDraftGenerationReceiptProcessor;
import com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor.ProductDraftGenerationResultProcessor;
import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationReceipt;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

/** 실제 작업 · outbox · 영수증 영속성과 트랜잭션 프록시. 워커 · AI · Kafka · 네트워크는 없다 (구성은 SizeChartGenerationRegistrationH2Test 와 같다). */
@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:product-draft-registration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;"
        + "INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.default_schema=operations",
    "operations.product-draft-generation.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ProductDraftGenerationRegistrationH2Test.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductDraftGenerationRegistrationH2Test {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    @Autowired
    private ProductDraftGenerationInternalFacade facade;
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
    private ProductDraftGenerationReceiptPort receipts;
    @Autowired
    private JobRegistrationProcessor registration;
    @Autowired
    private JobContextReadProcessor jobReader;
    @Autowired
    private ProductDraftGenerationReceiptProcessor receiptProcessor;
    @Autowired
    private ProductDraftGenerationResultProcessor resultProcessor;

    @BeforeEach
    void clean() {
        reset(receipts);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery("delete from ProductDraftGenerationReceiptEntity").executeUpdate();
            outbox.deleteAllInBatch();
            jobs.deleteAllInBatch();
        });
    }

    private ProductDraftGenerationRegisterCommand command(long draftId, int generationNo, String hash) {
        return new ProductDraftGenerationRegisterCommand(draftId, generationNo, hash, 7, NOW.plusSeconds(60));
    }

    private ProductDraftGenerationReceipt stored(long draftId, int generationNo) {
        return new TransactionTemplate(transactionManager).execute(status -> receipts.findByDraftIdAndGenerationNo(draftId, generationNo).orElseThrow());
    }

    @Test
    void duplicateSameRequestReturnsSameJobAndOneOutbox() {
        var command = command(10, 1, HASH);
        var first = facade.register(command);
        assertThat(facade.register(command)).isEqualTo(first);
        assertThat(first.inputHash()).isEqualTo(HASH);
        assertThat(first.generationNo()).isEqualTo(1);
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
        var receipt = stored(10, 1);
        assertThat(receipt).isEqualTo(new ProductDraftGenerationReceipt(10, 1, Long.parseLong(first.jobId()), HASH, 7, command.expiresAt()));
        var job = jobs.findById(receipt.jobId()).orElseThrow();
        assertThat(job.getType()).isEqualTo(ProductDraftGenerationJobProcessor.TYPE);
        assertThat(job.getDedupKey()).isEqualTo("product-draft:10:1");
        assertThat(job.getPayloadJson()).isEqualTo("{\"draftId\":10}");
    }

    @Test
    void eachGenerationOfTheSameDraftGetsItsOwnJobEvenWhileTheEarlierOneIsActive() {
        var first = facade.register(command(10, 1, HASH));
        var second = facade.register(command(10, 2, "b".repeat(64)));
        var third = facade.register(command(10, 3, HASH));
        assertThat(List.of(first.jobId(), second.jobId(), third.jobId())).doesNotHaveDuplicates();
        assertThat(second.generationNo()).isEqualTo(2);
        assertThat(jobs.count()).isEqualTo(3);
        assertThat(outbox.count()).isEqualTo(3);
        assertThat(facade.result(Long.parseLong(second.jobId())).generationNo()).isEqualTo(2);
        assertThat(facade.result(Long.parseLong(second.jobId())).status()).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-07T00:01:00.123456789Z", "2026-11-07T00:01:00.999999999Z"})
    void nanosRoundTripReplaysOriginalAndReloadedExpiry(String expiry) {
        Instant originalExpiry = Instant.parse(expiry);
        var original = new ProductDraftGenerationRegisterCommand(10, 1, HASH, 7, originalExpiry);
        var first = facade.register(original);
        var reloaded = stored(10, 1);
        assertThat(original.expiresAt()).isEqualTo(originalExpiry.truncatedTo(ChronoUnit.MICROS));
        assertThat(reloaded.expiresAt()).isEqualTo(original.expiresAt());
        assertThat(facade.register(original)).isEqualTo(first);
        assertThat(facade.register(new ProductDraftGenerationRegisterCommand(10, 1, HASH, 7, reloaded.expiresAt()))).isEqualTo(first);
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void originalAndActuallyReloadedRequestsReplayConcurrentlyWithoutExtraJobOrOutbox() throws Exception {
        var original = new ProductDraftGenerationRegisterCommand(10, 1, HASH, 7, NOW.plusSeconds(60).plusNanos(123456789));
        var first = facade.register(original);
        var receipt = stored(10, 1);
        var reloaded = new ProductDraftGenerationRegisterCommand(receipt.draftId(), receipt.generationNo(), receipt.inputHash(),
            receipt.requestedByMemberId(), receipt.expiresAt());
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
        var command = new ProductDraftGenerationRegisterCommand(10, 1, HASH, 7, NOW.plusSeconds(60).plusNanos(123456789));
        facade.register(command);
        var different = new ProductDraftGenerationRegisterCommand(10, 1, HASH, 7, command.expiresAt().plus(1, ChronoUnit.MICROS));
        assertCode(() -> facade.register(different), ProductDraftGenerationErrorCode.INPUT_CONFLICT);
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void conflictingHashReturns409AndLeavesOriginalUntouched() {
        var first = facade.register(command(10, 1, HASH));
        assertCode(() -> facade.register(command(10, 1, "b".repeat(64))), ProductDraftGenerationErrorCode.INPUT_CONFLICT);
        assertThat(facade.register(command(10, 1, HASH))).isEqualTo(first);
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void changedActorOrExpiryCannotReplayEvenWithSameHash() {
        var first = facade.register(command(10, 1, HASH));
        var changedActor = new ProductDraftGenerationRegisterCommand(10, 1, HASH, 8, NOW.plusSeconds(60));
        var changedExpiry = new ProductDraftGenerationRegisterCommand(10, 1, HASH, 7, NOW.plusSeconds(61));
        for (var changed : List.of(changedActor, changedExpiry)) {
            assertCode(() -> facade.register(changed), ProductDraftGenerationErrorCode.INPUT_CONFLICT);
        }
        assertThat(facade.register(command(10, 1, HASH))).isEqualTo(first);
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void completedReplayRemainsStableEvenAfterExpiryAndWhenRegistrationDisabled() {
        var first = facade.register(command(10, 1, HASH));
        long jobId = Long.parseLong(first.jobId());
        String result = "{\"suggestion\":{\"name\":\"n\"},\"unconfirmedFacts\":[],\"warnings\":[]}";
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var job = jobPort.findById(jobId).orElseThrow();
            var running = jobPort.update(job.claim("test-worker", NOW, Duration.ofMinutes(5)));
            jobPort.update(running.succeed(result, NOW.plusSeconds(1)));
        });
        var expiredClock = Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC);
        var expiredReceiptProcessor = new ProductDraftGenerationReceiptProcessor(receipts, resultProcessor, expiredClock);
        var replayFacade = new ProductDraftGenerationInternalFacade(registration, jobReader, expiredReceiptProcessor, resultProcessor,
            new ProductDraftGenerationInternalPresenter(), transactionManager, false);
        assertThat(replayFacade.register(command(10, 1, HASH))).isEqualTo(first);
        assertThat(replayFacade.result(jobId).status()).isEqualTo("SUCCEEDED");
        assertThat(replayFacade.result(jobId).inputHash()).isEqualTo(HASH);
        assertThat(replayFacade.result(jobId).resultJson()).isEqualTo(result);
        // 만료 뒤에는 같은 초안의 새 회차를 열 수 없다(플래그가 열려 있어도)
        var expiredOpen = new ProductDraftGenerationInternalFacade(registration, jobReader, expiredReceiptProcessor, resultProcessor,
            new ProductDraftGenerationInternalPresenter(), transactionManager, true);
        assertCode(() -> expiredOpen.register(command(10, 2, HASH)), ProductDraftGenerationErrorCode.EXPIRED);
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void expiredFirstRegistrationCreatesNothing() {
        var expired = new ProductDraftGenerationRegisterCommand(10, 1, HASH, 7, NOW);
        assertCode(() -> facade.register(expired), ProductDraftGenerationErrorCode.EXPIRED);
        assertThat(jobs.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void disabledRegistrationCreatesNothing() {
        var disabled = new ProductDraftGenerationInternalFacade(registration, jobReader, receiptProcessor, resultProcessor,
            new ProductDraftGenerationInternalPresenter(), transactionManager, false);
        assertCode(() -> disabled.register(command(10, 1, HASH)), ProductDraftGenerationErrorCode.UNAVAILABLE);
        assertThat(jobs.count()).isZero();
        assertThat(outbox.count()).isZero();
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
        assertCode(() -> facade.register(command(10, 1, HASH)), ProductDraftGenerationErrorCode.UNAVAILABLE);
        assertThat(observedAllWrites).isTrue();
        assertThat(jobs.count()).isZero();
        assertThat(outbox.count()).isZero();
        var missing = new TransactionTemplate(transactionManager).execute(status -> receipts.findByDraftIdAndGenerationNo(10, 1));
        assertThat(missing).isEmpty();
        reset(receipts);
        assertThat(facade.register(command(10, 1, HASH)).jobId()).isNotBlank();
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void simultaneousFirstRegistrationsReplayWinnerInFreshTransaction() throws Exception {
        race(command(10, 1, HASH), command(10, 1, HASH), false);
    }

    @Test
    void simultaneousDifferentHashesLeaveOneJobAndOne409() throws Exception {
        race(command(10, 1, HASH), command(10, 1, "b".repeat(64)), true);
    }

    @Test
    void simultaneousOriginalAndPersistencePrecisionRequestsReplaySameWinner() throws Exception {
        Instant expiry = NOW.plusSeconds(60).plusNanos(123456789);
        race(new ProductDraftGenerationRegisterCommand(10, 1, HASH, 7, expiry),
            new ProductDraftGenerationRegisterCommand(10, 1, HASH, 7, expiry.truncatedTo(ChronoUnit.MICROS)), false);
    }

    /**
     * 진 쪽(B)이 영수증 없음을 읽은 뒤 이긴 쪽(A)이 커밋하면, B 의 등록은 A 의 활성 작업을 받는다(created=false).
     * 요청자가 다르면 바인딩이 맞지 않아 경합 패배로 롤백하고 이긴 영수증과 대조해 409, 같으면 영수증 PK 충돌 뒤 같은 답을 재생한다.
     */
    @ParameterizedTest
    @ValueSource(longs = {8, 7})
    void loserThatReceivesTheCommittedWinnersActiveJobConflictsOrReplays(long loserActor) throws Exception {
        var winner = new ProductDraftGenerationRegisterCommand(10, 1, HASH, 7, NOW.plusSeconds(60));
        var loser = new ProductDraftGenerationRegisterCommand(10, 1, HASH, loserActor, NOW.plusSeconds(60));
        CountDownLatch loserReadAbsent = new CountDownLatch(1);
        CountDownLatch winnerCommitted = new CountDownLatch(1);
        AtomicReference<Thread> loserThread = new AtomicReference<>();
        AtomicBoolean paused = new AtomicBoolean();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (Thread.currentThread() == loserThread.get() && paused.compareAndSet(false, true)) {
                loserReadAbsent.countDown();
                assertThat(winnerCommitted.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(receipts).findByDraftIdAndGenerationNo(anyLong(), anyInt());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var loserCall = executor.submit(() -> {
                loserThread.set(Thread.currentThread());
                return attempt(loser);
            });
            assertThat(loserReadAbsent.await(10, TimeUnit.SECONDS)).isTrue();
            var first = facade.register(winner);
            winnerCommitted.countDown();
            Object outcome = loserCall.get(20, TimeUnit.SECONDS);
            if (loserActor == winner.actorMemberId()) {
                assertThat(outcome).isEqualTo(first);
            } else {
                assertThat(outcome).isEqualTo(ProductDraftGenerationErrorCode.INPUT_CONFLICT);
            }
            assertThat(stored(10, 1).jobId()).isEqualTo(Long.parseLong(first.jobId()));
            assertThat(stored(10, 1).requestedByMemberId()).isEqualTo(7);
        }
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    private void race(ProductDraftGenerationRegisterCommand original, ProductDraftGenerationRegisterCommand reloaded, boolean conflict) throws Exception {
        CyclicBarrier absentInBothTransactions = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (reads.getAndIncrement() < 2) {
                absentInBothTransactions.await(10, TimeUnit.SECONDS);
            }
            return result;
        }).when(receipts).findByDraftIdAndGenerationNo(anyLong(), anyInt());
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Object>> calls = List.of(() -> attempt(original), () -> attempt(reloaded));
            var tasks = calls.stream().map(executor::submit).toList();
            Object first = tasks.get(0).get(20, TimeUnit.SECONDS);
            Object second = tasks.get(1).get(20, TimeUnit.SECONDS);
            if (conflict) {
                assertThat(List.of(first, second)).filteredOn(value -> value == ProductDraftGenerationErrorCode.INPUT_CONFLICT).hasSize(1);
            } else {
                assertThat(first).isInstanceOf(ProductDraftGenerationRegistrationResponse.class);
                assertThat(first).isEqualTo(second);
            }
        }
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(reads.get()).isGreaterThanOrEqualTo(3);
    }

    private Object attempt(ProductDraftGenerationRegisterCommand command) {
        try {
            return facade.register(command);
        } catch (ProductDraftGenerationException exception) {
            return exception.getErrorCode();
        }
    }

    @Test
    void receiptIsInsertOnlyAndJobIdUnique() {
        facade.register(command(10, 1, HASH));
        var tx = new TransactionTemplate(transactionManager);
        var receipt = stored(10, 1);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> receipts.insert(receipt))).isInstanceOf(DataIntegrityViolationException.class);
        var reusedJob = new ProductDraftGenerationReceipt(10, 2, receipt.jobId(), HASH, 7, receipt.expiresAt());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> receipts.insert(reusedJob))).isInstanceOf(DataIntegrityViolationException.class);
        var missing = tx.execute(status -> receipts.findByDraftIdAndGenerationNo(10, 2));
        assertThat(missing).isEmpty();
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ProductDraftGenerationErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProductDraftGenerationException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EntityScan(basePackageClasses = {JobEntity.class, OutboxEntity.class, ProductDraftGenerationReceiptEntity.class})
    @EnableJpaRepositories(basePackageClasses = {JobRepository.class, OutboxRepository.class, ProductDraftGenerationReceiptRepository.class})
    @Import({JobRegistrationProcessor.class, JobRepositoryAdapter.class, JobDispatchEventAdapter.class, JpaOutboxStore.class,
        ProductDraftGenerationReceiptRepositoryAdapter.class, JobContextReadProcessor.class, ProductDraftGenerationReceiptProcessor.class,
        ProductDraftGenerationResultProcessor.class, ProductDraftGenerationInternalFacade.class, ProductDraftGenerationInternalPresenter.class})
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
        JobHandler productDraftTestHandler() {
            return JobFixtures.handler(ProductDraftGenerationJobProcessor.TYPE);
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
