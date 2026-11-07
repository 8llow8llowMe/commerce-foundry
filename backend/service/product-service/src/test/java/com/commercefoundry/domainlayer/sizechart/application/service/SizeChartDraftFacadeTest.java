package com.commercefoundry.domainlayer.sizechart.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.product.application.service.processor.ProductLookupProcessor;
import com.commercefoundry.domainlayer.sizechart.application.command.ProductSizeChartUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftApplyInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftUnavailableException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationRegistration;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationResult;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationStatus;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartGenerationPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartGenerationRegistrationQueryResult;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartGenerationResultQueryResult;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftApplyProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftRemoteProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftValidationProcessor;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class SizeChartDraftFacadeTest {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private final SizeChartDraftApplyProcessor applyProcessor = mock(SizeChartDraftApplyProcessor.class);
    private final SizeChartDraftProcessor processor = mock(SizeChartDraftProcessor.class);
    private final SizeChartGenerationPort generation = mock(SizeChartGenerationPort.class);
    private final SizeChartDraftValidationProcessor validation = new SizeChartDraftValidationProcessor();
    private final ProductLookupProcessor products = mock(ProductLookupProcessor.class);
    private final SizeChartDraftFacade facade = new SizeChartDraftFacade(
        applyProcessor, processor, validation, new SizeChartDraftRemoteProcessor(generation), products, Clock.fixed(NOW, ZoneOffset.UTC),
        new com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor(),
        new com.commercefoundry.domainlayer.sizechart.adapter.in.admin.presenter.SizeChartDraftAdminPresenter());
    private final SizeChartDraftCreateCommand command = new SizeChartDraftCreateCommand(10, 20, 40, UUID.randomUUID(), "TEXT", "S 50 cm");

    @Test
    void unknownRegistrationResponseKeepsRecoveryIdAndSameStoredDraftIsRetried() {
        var requested = draft();
        when(processor.createOrReplay(any(), any(), any())).thenReturn(requested);
        when(generation.register(requested)).thenThrow(new IllegalStateException("private provider body"));
        assertThatThrownBy(() -> facade.create(command)).isInstanceOfSatisfying(SizeChartDraftUnavailableException.class, e -> {
            assertThat(e.getDraftId()).isEqualTo(requested.id());
            assertThat(e.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.REGISTRATION_UNAVAILABLE);
            assertThat(e.getCause()).isNull();
            assertThat(e.getMessage()).doesNotContain("private");
        });
        verify(processor, never()).bind(any(), any());
        var receipt = registration();
        doReturn(receipt).when(generation).register(requested);
        when(processor.bind(requested, registrationValue(receipt))).thenReturn(requested.bindJob(200, NOW));
        assertThat(facade.create(command).jobId()).isEqualTo(200);
        verify(generation, times(2)).register(requested);
    }

    @Test
    void uniqueInsertFailureReplaysWinnerAfterFailedCallHasExited() {
        var bound = draft().bindJob(200, NOW);
        when(processor.createOrReplay(any(), any(), any())).thenThrow(new DataIntegrityViolationException("unique"));
        when(processor.replay(any(), any())).thenReturn(Optional.empty(), Optional.of(bound));
        assertThat(facade.create(command).draftId()).isEqualTo(bound.id());
        var order = inOrder(processor);
        order.verify(processor).replay(any(), any());
        order.verify(processor).createOrReplay(any(), any(), any());
        order.verify(processor).replay(any(), any());
        verifyNoInteractions(generation);
    }

    @Test
    void malformedRegistrationCannotBindAndReturnsRecoverable503() {
        when(processor.createOrReplay(any(), any(), any())).thenReturn(draft());
        when(generation.register(any())).thenReturn(new SizeChartGenerationRegistrationQueryResult(999, 200, "a".repeat(64)));
        assertThatThrownBy(() -> facade.create(command)).isInstanceOf(SizeChartDraftUnavailableException.class);
        verify(processor, never()).bind(any(), any());
    }

    @Test
    void localGetAndListNeverRegisterFetchOrMutate() {
        when(processor.get(100)).thenReturn(draft());
        when(processor.list(null, 40L, 0, 20)).thenReturn(Page.empty());
        assertThat(facade.get(100).status()).isEqualTo(SizeChartDraftStatus.REQUESTED);
        assertThat(facade.list(null, 40L, 0, 20)).isEmpty();
        verifyNoInteractions(generation);
        verify(processor, never()).bind(any(), any());
        verify(processor, never()).complete(any(), any(), any());
    }

    @Test
    void explicitRefreshReregistersUnboundThenFetchesResult() {
        var requested = draft();
        var bound = requested.bindJob(200, NOW);
        var receipt = registration();
        var result = new SizeChartGenerationResultQueryResult(100, 200, "a".repeat(64), SizeChartGenerationStatus.PENDING, null, null);
        when(processor.get(100)).thenReturn(requested);
        when(generation.register(requested)).thenReturn(receipt);
        when(processor.bind(requested, registrationValue(receipt))).thenReturn(bound);
        when(generation.result(bound)).thenReturn(result);
        when(processor.complete(bound, resultValue(result), null)).thenReturn(bound);
        assertThat(facade.refresh(100, 40).status()).isEqualTo(SizeChartDraftStatus.REQUESTED);
        var order = inOrder(generation, processor);
        order.verify(processor).get(100);
        order.verify(generation).register(requested);
        order.verify(processor).bind(requested, registrationValue(receipt));
        order.verify(generation).result(bound);
        order.verify(processor).verifyResult(bound, resultValue(result));
        order.verify(processor).complete(bound, resultValue(result), null);
    }

    @Test
    void optimisticBindingRaceRereadsWinnerWithoutSecondRemoteRegistration() {
        var requested = draft();
        var receipt = registration();
        when(processor.createOrReplay(any(), any(), any())).thenReturn(requested);
        when(generation.register(requested)).thenReturn(receipt);
        when(processor.bind(requested, registrationValue(receipt))).thenThrow(new ObjectOptimisticLockingFailureException(SizeChartDraft.class, 100L))
            .thenReturn(requested.bindJob(200, NOW));
        assertThat(facade.create(command).jobId()).isEqualTo(200);
        verify(generation).register(requested);
        verify(processor, times(2)).bind(requested, registrationValue(receipt));
    }

    @Test
    void refreshOnTerminalDraftIsLocalAndNeverApplies() {
        var failed = draft().bindJob(200, NOW).fail(NOW);
        when(processor.get(100)).thenReturn(failed);
        assertThat(facade.refresh(100, 40).status()).isEqualTo(SizeChartDraftStatus.FAILED);
        verifyNoInteractions(generation);
    }

    @Test
    void requestedApplyRefreshesBeforeEnteringApplyTransactionAndDoesNotSubstituteExpectedVersion() {
        var requested = draft().bindJob(200, NOW);
        var result = new SizeChartGenerationResultQueryResult(100, 200, "a".repeat(64), SizeChartGenerationStatus.PENDING, null, null);
        when(processor.get(100)).thenReturn(requested);
        when(generation.result(requested)).thenReturn(result);
        when(processor.complete(requested, resultValue(result), null)).thenReturn(requested);
        var chart = new ProductSizeChartUpsertCommand(20, null, List.of(new ProductSizeChartUpsertCommand.Row("S", 0,
            List.of(new ProductSizeChartUpsertCommand.Cell(30, new BigDecimal("50"))))));
        var receipt = new SizeChartDraftApplyInfo(100, 10, 50, 0, 40, NOW);
        when(applyProcessor.apply(any(), any())).thenAnswer(i -> {
            com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftApplyCommand apply = i.getArgument(0);
            assertThat(apply.expectedDraftVersion()).isEqualTo(7);
            return receipt;
        });
        assertThat(facade.apply(100, 7, chart, Set.of(), 40)).isEqualTo(receipt);
        var order = inOrder(generation, processor, applyProcessor);
        order.verify(generation).result(requested);
        order.verify(processor).verifyResult(requested, resultValue(result));
        order.verify(processor).complete(requested, resultValue(result), null);
        order.verify(applyProcessor).apply(any(), any());
    }

    private static SizeChartGenerationRegistration registrationValue(
        SizeChartGenerationRegistrationQueryResult result) {
        return new SizeChartGenerationRegistration(result.draftId(), result.jobId(), result.inputHash());
    }

    private static SizeChartGenerationResult resultValue(SizeChartGenerationResultQueryResult result) {
        return new SizeChartGenerationResult(result.draftId(), result.jobId(), result.inputHash(), result.status(), result.failureCode(), result.resultJson());
    }

    private SizeChartDraft draft() {
        return SizeChartDraft.request(100, new SizeChartDraft.Input(10, 20, 3, 40, command.idempotencyKey(),
            validation.normalizedRequestHash(command), "a".repeat(64), "{\"mode\":\"TEXT\",\"text\":\"S 50 cm\"}",
            "{\"unit\":\"CM\",\"columns\":[{\"id\":30,\"name\":\"Chest\"}]}", null, null), NOW.plusSeconds(3600));
    }

    private SizeChartGenerationRegistrationQueryResult registration() {
        return new SizeChartGenerationRegistrationQueryResult(100, 200, "a".repeat(64));
    }
}
