package com.commercefoundry.domainlayer.sizechart.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.product.application.service.processor.ProductLookupProcessor;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.SizeChartDraftRepositoryAdapter;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository.SizeChartDraftRepository;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftUnavailableException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationRegistration;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationResult;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationStatus;
import com.commercefoundry.domainlayer.sizechart.application.port.out.ProductSizeChartRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartDraftRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartGenerationPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartTemplateRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartGenerationRegistrationQueryResult;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartGenerationResultQueryResult;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftCleanupProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftInputProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftRemoteProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftValidationProcessor;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeChart;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplate;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplateColumn;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.global.config.SizeChartDraftProperties;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real H2 constraints, transaction boundaries and version races; no operations or provider connection. */
@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:size-chart-application;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product",
    "size-chart.draft.content-ttl=7d"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class, SizeChartDraftRepositoryAdapter.class,
    com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor.class,
    com.commercefoundry.domainlayer.sizechart.adapter.in.admin.presenter.SizeChartDraftAdminPresenter.class,
    SizeChartDraftProcessor.class, SizeChartDraftValidationProcessor.class, SizeChartDraftRemoteProcessor.class, SizeChartDraftFacade.class,
    SizeChartDraftProperties.class, SizeChartDraftInputProcessor.class, SizeChartDraftCleanupProcessor.class,
    SizeChartDraftSchedulerFacade.class, SizeChartDraftApplicationPersistenceTest.TimeConfig.class})
class SizeChartDraftApplicationPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    @Autowired private SizeChartDraftFacade facade;
    @Autowired private SizeChartDraftRepositoryPort port;
    @Autowired private SizeChartDraftRepository repository;
    @Autowired private SizeChartDraftProcessor processor;
    @Autowired private SizeChartDraftSchedulerFacade cleanup;
    @Autowired private SizeChartDraftInputProcessor inputProcessor;
    @MockitoBean private Clock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private SizeChartTemplateRepositoryPort templates;
    @MockitoBean private ProductSizeChartRepositoryPort charts;
    @MockitoBean private ProductLookupProcessor products;
    @MockitoBean private SnowflakeIdGenerator ids;
    @MockitoBean private SizeChartGenerationPort generation;
    @MockitoBean private com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftApplyProcessor applyProcessor;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(NOW);
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> repository.deleteAllInBatch());
        AtomicLong sequence = new AtomicLong(100);
        when(ids.generateId()).thenAnswer(ignored -> sequence.incrementAndGet());
        when(products.findAllIncludingHidden(List.of(10L))).thenReturn(Map.of(10L, mock(Product.class)));
        when(templates.lockById(20)).thenReturn(Optional.of(new SizeChartTemplate(20, "T", SizeChartUnit.CM, 3)));
        when(templates.findColumns(20)).thenReturn(List.of(new SizeChartTemplateColumn(30, 20, "Chest", null, 0)));
        when(charts.findByProductId(10)).thenReturn(Optional.of(new ProductSizeChart(50, 10, 20, null, 7)));
        when(generation.register(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            SizeChartDraft draft = invocation.getArgument(0);
            // The remote input reader must be able to see an already committed draft.
            assertThat(port.findById(draft.id())).isPresent();
            return registration(draft);
        });
    }

    @Test
    void nanosecondClockKeepsPersistedAndRemoteExpiryIdenticalAcrossLostResponseReplay() {
        Instant preciseNow = NOW.plusNanos(123456789);
        when(clock.instant()).thenReturn(preciseNow);
        var command = command(40, UUID.randomUUID(), "S Chest 50cm");
        var expiries = new java.util.ArrayList<Instant>();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            SizeChartDraft draft = invocation.getArgument(0);
            expiries.add(draft.expiresAt());
            assertThat(port.findById(draft.id()).orElseThrow().expiresAt()).isEqualTo(draft.expiresAt());
            if (expiries.size() == 1) {
                throw new IllegalStateException("response lost after remote registration");
            }
            return registration(draft);
        }).when(generation).register(any());
        assertThatThrownBy(() -> facade.create(command)).isInstanceOf(SizeChartDraftUnavailableException.class);
        var recovered = facade.create(command);
        var expected = preciseNow.plus(Duration.ofDays(7)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        assertThat(expiries).containsExactly(expected, expected);
        assertThat(recovered.expiresAt()).isEqualTo(expected);
        assertThat(facade.create(command)).isEqualTo(recovered);
        assertThat(repository.count()).isEqualTo(1);
    }

    private SizeChartGenerationResult applicationResult(
        SizeChartDraft draft, SizeChartGenerationStatus status) {
        var value = result(draft, status);
        return new SizeChartGenerationResult(
            value.draftId(), value.jobId(), value.inputHash(), value.status(), value.failureCode(), value.resultJson());
    }

    @Test
    void actorKeyReplaysBeforeLiveReadsEvenWhenTemplateWasChangedOrDeleted() {
        var command = command(40, UUID.randomUUID(), "S Chest 50cm");
        var created = facade.create(command);
        var stored = port.findById(created.draftId()).orElseThrow();
        assertThat(stored.input().templateRevision()).isEqualTo(3);
        assertThat(stored.input().baseChartId()).isEqualTo(50);
        assertThat(stored.input().baseChartRevision()).isEqualTo(7);
        assertThat(created.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(stored.input().templateJson()).isEqualTo("{\"unit\":\"CM\",\"columns\":[{\"id\":30,\"name\":\"Chest\"}]}");
        reset(products, templates, charts);
        clearInvocations(generation);
        assertThat(facade.create(command)).isEqualTo(created);
        verifyNoInteractions(products, templates, charts, generation);
        assertThatThrownBy(() -> facade.create(command(40, command.idempotencyKey(), "S Chest 51cm")))
            .isInstanceOfSatisfying(SizeChartDraftTransportException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.INPUT_CONFLICT));
        verifyNoInteractions(products, templates, charts, generation);
    }

    @Test
    void lostResponseLeavesRequestedAndRefreshRecoversSameDraftWithoutSourcePii() {
        var command = command(40, UUID.randomUUID(), "S 50cm sample@example.invalid 010-1234-5678");
        doThrow(new IllegalStateException("sensitive transport body")).when(generation).register(any());
        final AtomicLong recoveryId = new AtomicLong();
        assertThatThrownBy(() -> facade.create(command)).isInstanceOfSatisfying(SizeChartDraftUnavailableException.class,
            e -> recoveryId.set(e.getDraftId()));
        SizeChartDraft stored = port.findById(recoveryId.get()).orElseThrow();
        assertThat(stored.status()).isEqualTo(SizeChartDraftStatus.REQUESTED);
        assertThat(stored.jobId()).isNull();
        assertThat(stored.input().sourceJson()).doesNotContain("sample", "example", "010", "1234");
        doAnswer(i -> registration(i.getArgument(0))).when(generation).register(any());
        when(generation.result(any())).thenAnswer(i -> result(i.getArgument(0), SizeChartGenerationStatus.SUCCEEDED));
        assertThat(facade.refresh(stored.id(), command.actorId()).status()).isEqualTo(SizeChartDraftStatus.READY);
        assertThat(facade.create(command).draftId()).isEqualTo(stored.id());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void localListUsesActualCombinedQueryAndNewestFirstWhileGetDoesNotWrite() {
        var first = facade.create(command(40, UUID.randomUUID(), "S 50cm"));
        var second = facade.create(command(41, UUID.randomUUID(), "S 51cm"));
        var third = facade.create(command(40, UUID.randomUUID(), "S 52cm"));
        reset(generation, products, templates, charts);
        assertThat(facade.list(null, null, 0, 2).map(d -> d.draftId()).getContent()).containsExactly(third.draftId(), second.draftId());
        assertThat(facade.list(10L, 40L, 0, 20).map(d -> d.draftId()).getContent()).containsExactly(third.draftId(), first.draftId());
        assertThat(facade.list(999L, null, 0, 20)).isEmpty();
        assertThat(facade.list(null, 999L, 0, 20)).isEmpty();
        assertThat(facade.list(null, null, 1, 2).getTotalElements()).isEqualTo(3);
        SizeChartDraft before = port.findById(first.draftId()).orElseThrow();
        facade.get(first.draftId());
        facade.get(first.draftId());
        assertThat(port.findById(first.draftId())).contains(before);
        verifyNoInteractions(generation, products, templates, charts);
    }

    @Test
    void concurrentCreateAndRefreshConvergeOnOneDraftJobAndReadyVersion() throws Exception {
        var command = command(40, UUID.randomUUID(), "S 50cm");
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return facade.create(command); });
            var second = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return facade.create(command); });
            var winner = first.get(20, TimeUnit.SECONDS);
            assertThat(second.get(20, TimeUnit.SECONDS).draftId()).isEqualTo(winner.draftId());
            assertThat(repository.count()).isEqualTo(1);
            CyclicBarrier results = new CyclicBarrier(2);
            when(generation.result(any())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                results.await(10, TimeUnit.SECONDS);
                return result(i.getArgument(0), SizeChartGenerationStatus.SUCCEEDED);
            });
            var refreshOne = executor.submit(() -> facade.refresh(winner.draftId(), command.actorId()));
            var refreshTwo = executor.submit(() -> facade.refresh(winner.draftId(), command.actorId()));
            assertThat(refreshOne.get(20, TimeUnit.SECONDS).status()).isEqualTo(SizeChartDraftStatus.READY);
            assertThat(refreshTwo.get(20, TimeUnit.SECONDS).status()).isEqualTo(SizeChartDraftStatus.READY);
            assertThat(port.findById(winner.draftId()).orElseThrow().version()).isEqualTo(2);
        }
    }

    @Test
    void staleBindingAndResultCannotOverwriteCommittedState() {
        var info = facade.create(command(40, UUID.randomUUID(), "S 50cm"));
        var observed = port.findById(info.draftId()).orElseThrow();
        assertThatThrownBy(() -> processor.bind(observed, new SizeChartGenerationRegistration(observed.id(), 999, observed.input().inputHash())))
            .isInstanceOfSatisfying(SizeChartDraftTransportException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.BINDING_CONFLICT));
        var failed = processor.complete(observed, applicationResult(observed, SizeChartGenerationStatus.FAILED), null);
        assertThat(failed.status()).isEqualTo(SizeChartDraftStatus.FAILED);
        assertThat(processor.complete(observed, applicationResult(observed, SizeChartGenerationStatus.CANCELLED), null)).isEqualTo(failed);
    }

    @Test
    void sevenDayBoundaryHidesContentAndRejectsReplayRefreshAndGenerationInputBeforeCleanup() {
        var command = command(40, UUID.randomUUID(), "S Chest 50cm");
        var info = facade.create(command);
        var requested = port.findById(info.draftId()).orElseThrow();
        processor.complete(requested, applicationResult(requested, SizeChartGenerationStatus.SUCCEEDED),
            new SizeChartDraftValidationProcessor().candidate(result(requested, SizeChartGenerationStatus.SUCCEEDED).resultJson(),
                requested.input().templateJson()));
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(7)).minusMillis(1));
        assertThat(facade.get(info.draftId()).candidateJson()).isNotNull();
        assertThat(facade.list(null, null, 0, 20).getTotalElements()).isEqualTo(1);
        assertThat(cleanup.deleteExpiredDrafts()).isZero();
        clearInvocations(generation);
        when(clock.instant()).thenReturn(info.expiresAt());
        expired(() -> facade.get(info.draftId()));
        expired(() -> facade.getAdmin(info.draftId()));
        expired(() -> facade.refresh(info.draftId(), 40));
        expired(() -> facade.create(command));
        expired(() -> facade.create(command(40, command.idempotencyKey(), "S Chest 51cm")));
        expired(() -> inputProcessor.generationInput(info.draftId()));
        assertThat(facade.list(null, null, 0, 20).getTotalElements()).isZero();
        assertThat(facade.list(10L, 40L, 0, 20)).isEmpty();
        assertThat(repository.count()).isEqualTo(1); // Suppression does not depend on the cleanup schedule.
        verifyNoInteractions(generation);
    }

    @Test
    void cleanupAtSevenDaysReleasesActorKeyAndNewDraftGetsNewJobAndId() {
        var command = command(40, UUID.randomUUID(), "S Chest 50cm");
        var original = facade.create(command);
        when(clock.instant()).thenReturn(original.expiresAt().minusMillis(1));
        assertThat(cleanup.deleteExpiredDrafts()).isZero();
        assertThat(facade.create(command).draftId()).isEqualTo(original.draftId());
        when(clock.instant()).thenReturn(original.expiresAt());
        expired(() -> facade.create(command));
        assertThat(cleanup.deleteExpiredDrafts()).isEqualTo(1);
        assertThat(port.findById(original.draftId())).isEmpty();
        assertThat(cleanup.deleteExpiredDrafts()).isZero();
        var replacement = facade.create(command);
        assertThat(replacement.draftId()).isNotEqualTo(original.draftId());
        assertThat(replacement.jobId()).isNotEqualTo(original.jobId());
        assertThat(replacement.expiresAt()).isEqualTo(original.expiresAt().plus(Duration.ofDays(7)));
        assertThat(cleanup.deleteExpiredDrafts()).isZero();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void concurrentCleanupInstancesDeleteEachExpiredRowOnlyOnce() throws Exception {
        var created = facade.create(command(40, UUID.randomUUID(), "S Chest 50cm"));
        when(clock.instant()).thenReturn(created.expiresAt());
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return cleanup.deleteExpiredDrafts(); });
            var second = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return cleanup.deleteExpiredDrafts(); });
            assertThat(first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(repository.count()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Alice Smith", "Maple Avenue", "Chest Alice"})
    void unsafeTemplateNamesAreRejectedBeforeGenerationOrDraftStorage(String name) {
        when(templates.findColumns(20)).thenReturn(List.of(new SizeChartTemplateColumn(30, 20, name, null, 0)));
        assertThatThrownBy(() -> facade.create(command(40, UUID.randomUUID(), "S " + name + " 50")))
            .isInstanceOfSatisfying(SizeChartDraftTransportException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.UNSAFE_INPUT));
        verifyNoInteractions(generation);
        assertThat(repository.count()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"S Chest 50 Alice Smith", "S Chest 50 Maple Avenue", "please make a size chart"})
    void unknownSourceTextIsRejectedBeforeGenerationOrDraftStorage(String text) {
        assertThatThrownBy(() -> facade.create(command(40, UUID.randomUUID(), text)))
            .isInstanceOfSatisfying(SizeChartDraftTransportException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.UNSAFE_INPUT));
        verifyNoInteractions(generation);
        assertThat(repository.count()).isZero();
    }

    private void expired(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SizeChartDraftTransportException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.EXPIRED);
            assertThat(e.getErrorCode().getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.GONE);
            assertThat(e.getMessage()).doesNotContain("Chest", "sourceJson", "templateJson", "candidateJson");
            assertThat(e.getCause()).isNull();
        });
    }

    private SizeChartDraftCreateCommand command(long actor, UUID key, String text) {
        return new SizeChartDraftCreateCommand(10, 20, actor, key, "TEXT", text);
    }

    private SizeChartGenerationRegistrationQueryResult registration(SizeChartDraft draft) {
        return new SizeChartGenerationRegistrationQueryResult(draft.id(), draft.id() + 10000, draft.input().inputHash());
    }

    private SizeChartGenerationResultQueryResult result(SizeChartDraft draft, SizeChartGenerationStatus status) {
        String json = status == SizeChartGenerationStatus.SUCCEEDED
            ? "{\"unit\":\"CM\",\"rows\":[{\"sizeLabel\":\"S\",\"cells\":[{\"columnId\":30,\"value\":50}]}],\"warnings\":[]}" : null;
        return new SizeChartGenerationResultQueryResult(draft.id(), draft.jobId(), draft.input().inputHash(), status,
            status == SizeChartGenerationStatus.FAILED ? "GENERATION_FAILED" : null, json);
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
