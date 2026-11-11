package com.commercefoundry.domainlayer.sizechart.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductLookupProcessor;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftApplyInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftUnavailableException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationResult;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationStatus;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartGenerationPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartGenerationResultQueryResult;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftApplyProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftRemoteProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftValidationProcessor;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.security.audit.AdminAuditLog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionSystemException;

/** SecurityContext 없이도 전달한 운영자의 실제 성공/실패 감사 줄을 검증한다. */
class SizeChartDraftFacadeAuditTest {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private final SizeChartDraftProcessor processor = mock(SizeChartDraftProcessor.class);
    private final SizeChartDraftApplyProcessor apply = mock(SizeChartDraftApplyProcessor.class);
    private final SizeChartGenerationPort generation = mock(SizeChartGenerationPort.class);
    private final SizeChartDraftValidationProcessor validation = new SizeChartDraftValidationProcessor();
    private final ProductLookupProcessor products = mock(ProductLookupProcessor.class);
    private final SizeChartDraftFacade facade = new SizeChartDraftFacade(apply, processor, validation, new SizeChartDraftRemoteProcessor(generation), products,
        Clock.fixed(NOW, ZoneOffset.UTC), new ProductAdminAuditProcessor(),
        new com.commercefoundry.domainlayer.sizechart.adapter.in.admin.presenter.SizeChartDraftAdminPresenter(),
        mock(com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartImageSourceProcessor.class),
        mock(com.commercefoundry.domainlayer.product.application.service.processor.ProductImageLookupProcessor.class),
        new com.commercefoundry.global.config.SizeChartDraftProperties());
    private final SizeChartDraftCreateCommand command = new SizeChartDraftCreateCommand(10, 20, 40, UUID.randomUUID(), "TEXT", "S 50 cm");
    private final Logger logger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
    private final ListAppender<ILoggingEvent> audit = new ListAppender<>();

    @BeforeEach
    void attach() {
        audit.start();
        logger.addAppender(audit);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(audit);
        audit.stop();
    }

    @Test
    void createSuccessAndLostRegistrationResponseAreBothAudited() {
        var bound = draft().bindJob(200, NOW);
        when(processor.createOrReplay(any(), any(), any(), any())).thenReturn(bound).thenReturn(draft());
        when(generation.register(any())).thenThrow(new IllegalStateException("private provider source"));
        facade.create(command);
        assertThatThrownBy(() -> facade.create(command)).isInstanceOf(SizeChartDraftUnavailableException.class);
        assertThat(lines()).containsExactly(
            "action=SIZE_CHART_DRAFT_CREATE actorMemberId=40 target=product:10 result=SUCCESS",
            "action=SIZE_CHART_DRAFT_CREATE actorMemberId=40 target=product:10 result=FAILURE");
    }

    @Test
    void explicitRefreshUsesCallingOperatorAndAuditsCommitFailure() {
        var bound = draft().bindJob(200, NOW);
        when(processor.get(100)).thenReturn(bound.fail(NOW)).thenReturn(bound);
        var result = new SizeChartGenerationResultQueryResult(100, 200, "a".repeat(64), SizeChartGenerationStatus.PENDING, null, null);
        when(generation.result(bound)).thenReturn(result);
        when(processor.complete(bound, resultValue(result), null)).thenThrow(new TransactionSystemException("private commit diagnostics"));
        facade.refresh(100, 77);
        assertThatThrownBy(() -> facade.refresh(100, 77)).isInstanceOf(TransactionSystemException.class);
        assertThat(lines()).containsExactly(
            "action=SIZE_CHART_DRAFT_REFRESH actorMemberId=77 target=size-chart-draft:100 result=SUCCESS",
            "action=SIZE_CHART_DRAFT_REFRESH actorMemberId=77 target=size-chart-draft:100 result=FAILURE");
    }

    @Test
    void applyInternalRefreshDoesNotCreateExtraOperatorAuditAndCommitFailureIsFailure() {
        var bound = draft().bindJob(200, NOW);
        when(processor.get(100)).thenReturn(bound);
        var result = new SizeChartGenerationResultQueryResult(100, 200, "a".repeat(64), SizeChartGenerationStatus.PENDING, null, null);
        when(generation.result(bound)).thenReturn(result);
        when(processor.complete(bound, resultValue(result), null)).thenReturn(bound);
        when(apply.apply(any(), any())).thenReturn(new SizeChartDraftApplyInfo(100, 10, 50, 1, 77, NOW))
            .thenThrow(new TransactionSystemException("private candidate diagnostics"));
        facade.apply(100, 2, null, Set.of(), 77);
        assertThatThrownBy(() -> facade.apply(100, 2, null, Set.of(), 77)).isInstanceOf(TransactionSystemException.class);
        assertThat(lines()).containsExactly(
            "action=SIZE_CHART_DRAFT_APPLY actorMemberId=77 target=size-chart-draft:100 result=SUCCESS",
            "action=SIZE_CHART_DRAFT_APPLY actorMemberId=77 target=size-chart-draft:100 result=FAILURE");
    }

    @Test
    void invalidInputIsAuditedBeforeDraftExists() {
        assertThatThrownBy(() -> facade.create(new SizeChartDraftCreateCommand(10, 20, 40, command.idempotencyKey(), "IMAGE", "private source")))
            .isInstanceOf(SizeChartDraftTransportException.class);
        assertThat(lines()).containsExactly("action=SIZE_CHART_DRAFT_CREATE actorMemberId=40 target=product:10 result=FAILURE");
    }

    private List<String> lines() {
        return audit.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static SizeChartGenerationResult resultValue(SizeChartGenerationResultQueryResult result) {
        return new SizeChartGenerationResult(result.draftId(), result.jobId(), result.inputHash(), result.status(), result.failureCode(), result.resultJson());
    }

    private SizeChartDraft draft() {
        return SizeChartDraft.request(100, new SizeChartDraft.Input(10, 20, 3, 40, command.idempotencyKey(),
            validation.normalizedRequestHash(command), "a".repeat(64), "{\"text\":\"private source\"}",
            "{\"unit\":\"CM\",\"columns\":[{\"id\":30,\"name\":\"Chest\"}]}", null, null), NOW.plusSeconds(3600));
    }
}
