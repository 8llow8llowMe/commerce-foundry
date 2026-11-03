package com.commercefoundry.domainlayer.knowledge.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.adminaudit.application.model.SupportAdminAuditAction;
import com.commercefoundry.domainlayer.adminaudit.application.service.processor.SupportAdminAuditProcessor;
import com.commercefoundry.domainlayer.knowledge.adapter.in.admin.controller.KnowledgeAdminController;
import com.commercefoundry.domainlayer.knowledge.adapter.in.admin.dto.request.KnowledgeAdminRequests;
import com.commercefoundry.domainlayer.knowledge.adapter.in.admin.presenter.KnowledgeAdminPresenter;
import com.commercefoundry.domainlayer.knowledge.application.command.KnowledgeContentCommand;
import com.commercefoundry.domainlayer.knowledge.application.command.KnowledgeCreateCommand;
import com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeErrorCode;
import com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeException;
import com.commercefoundry.domainlayer.knowledge.application.info.KnowledgeAdminInfo;
import com.commercefoundry.domainlayer.knowledge.application.service.processor.KnowledgeProcessor;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeCategory;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeDocument;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeRevision;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeStatus;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeType;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.audit.AdminAuditLog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class KnowledgeAdminFacadeTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final KnowledgeContentCommand CONTENT = new KnowledgeContentCommand("Private title", "Private body", KnowledgeCategory.SHIPPING, NOW, null);
    private final KnowledgeProcessor processor = mock(KnowledgeProcessor.class);
    private final SupportAdminAuditProcessor auditProcessor = new SupportAdminAuditProcessor();
    private final KnowledgeAdminFacade facade = new KnowledgeAdminFacade(processor, new KnowledgeAdminPresenter(), auditProcessor, Clock.fixed(NOW, ZoneOffset.UTC));
    private final Logger logger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
    private ListAppender<ILoggingEvent> audit;

    @BeforeEach
    void attachAndStubBusinessResults() {
        audit = new ListAppender<>();
        audit.start();
        logger.addAppender(audit);
        KnowledgeAdminInfo info = new KnowledgeAdminInfo(new KnowledgeDocument(101, KnowledgeType.FAQ, KnowledgeLocale.KO, 1, null, 0L),
            KnowledgeRevision.draft(102, 101, 1, new KnowledgeRevision.Content(CONTENT.title(), CONTENT.body(), CONTENT.category(), NOW, null)));
        when(processor.createAdmin(any(), any(), any())).thenReturn(info);
        when(processor.editAdmin(anyLong(), anyInt(), any(), any())).thenReturn(info);
        when(processor.createDraftAdmin(anyLong(), any(), any())).thenReturn(info);
        when(processor.approveAdmin(anyLong(), anyInt(), any(), anyLong(), any())).thenReturn(info);
        when(processor.publishAdmin(anyLong(), anyInt(), any(), any(), any())).thenReturn(info);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(audit);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @ParameterizedTest
    @EnumSource(SupportAdminAuditAction.class)
    void everyBusinessSuccessWritesOneSafeAuditLine(SupportAdminAuditAction action) {
        invoke(action);
        assertThat(audit.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage()).isEqualTo(
            "action=" + action + " actorMemberId=99 target=knowledge:101 result=SUCCESS"));
    }

    static Stream<Arguments> failures() {
        return Arrays.stream(SupportAdminAuditAction.values()).flatMap(action -> Stream.of(
            new KnowledgeException(KnowledgeErrorCode.NOT_FOUND), new KnowledgeException(KnowledgeErrorCode.CONCURRENTLY_CHANGED),
            new IllegalStateException("Private title Private body"))
            .map(failure -> Arguments.of(action, failure)));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void notFoundConflictAndUnexpectedFailuresAllWriteFailureAndRethrow(SupportAdminAuditAction action, RuntimeException failure) {
        switch (action) {
            case KNOWLEDGE_CREATE -> when(processor.createAdmin(any(), any(), any())).thenThrow(failure);
            case KNOWLEDGE_EDIT -> when(processor.editAdmin(anyLong(), anyInt(), any(), any())).thenThrow(failure);
            case KNOWLEDGE_DRAFT_CREATE -> when(processor.createDraftAdmin(anyLong(), any(), any())).thenThrow(failure);
            case KNOWLEDGE_APPROVE -> when(processor.approveAdmin(anyLong(), anyInt(), any(), anyLong(), any())).thenThrow(failure);
            case KNOWLEDGE_PUBLISH -> when(processor.publishAdmin(anyLong(), anyInt(), any(), any(), any())).thenThrow(failure);
            case KNOWLEDGE_ARCHIVE -> doThrow(failure).when(processor).archiveAdmin(anyLong(), anyInt(), any(), any());
        }
        assertThatThrownBy(() -> invoke(action)).isSameAs(failure);
        String target = action == SupportAdminAuditAction.KNOWLEDGE_CREATE ? "-" : "knowledge:101";
        assertThat(audit.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage()).isEqualTo(
            "action=" + action + " actorMemberId=99 target=" + target + " result=FAILURE"));
    }

    @Test
    void approvalAndPublicationUseTheServerClockAndAuthenticatedActor() {
        invoke(SupportAdminAuditAction.KNOWLEDGE_APPROVE);
        invoke(SupportAdminAuditAction.KNOWLEDGE_PUBLISH);
        verify(processor).approveAdmin(101, 1, 0L, 99, NOW);
        verify(processor).publishAdmin(101, 1, 0L, 0L, NOW);
    }

    @Test
    void declarationsMatchEnumAndAllRequestLoggingIsRedacted() {
        var declarations = Arrays.stream(KnowledgeAdminController.class.getDeclaredMethods())
            .map(method -> method.getAnnotation(AdminAudited.class)).filter(annotation -> annotation != null).map(AdminAudited::action).toList();
        assertThat(declarations).containsExactlyInAnyOrderElementsOf(Arrays.stream(SupportAdminAuditAction.values()).map(Enum::name).toList());
        var content = new KnowledgeAdminRequests.Content(CONTENT.title(), CONTENT.body(), CONTENT.category(), NOW, null);
        assertThat(new KnowledgeAdminRequests.Create(KnowledgeType.FAQ, KnowledgeLocale.KO, content).toString())
            .doesNotContain(CONTENT.title(), CONTENT.body());
        assertThat(new KnowledgeAdminRequests.Edit(1, 0L, content).toString()).doesNotContain(CONTENT.title(), CONTENT.body());
        assertThat(new KnowledgeAdminRequests.Draft(0L, content).toString()).doesNotContain(CONTENT.title(), CONTENT.body());
        assertThat(new KnowledgeCreateCommand(KnowledgeType.FAQ, KnowledgeLocale.KO, CONTENT).toString()).doesNotContain(CONTENT.title(), CONTENT.body());
    }

    @Test
    void auditWaitsForTransactionCompletionAndRollbackCannotBeSuccess() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        auditProcessor.created(99, 101L, true);
        assertThat(audit.list).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(audit.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage()).endsWith("result=FAILURE"));
    }

    private void invoke(SupportAdminAuditAction action) {
        switch (action) {
            case KNOWLEDGE_CREATE -> facade.create(new KnowledgeCreateCommand(KnowledgeType.FAQ, KnowledgeLocale.KO, CONTENT), 99);
            case KNOWLEDGE_EDIT -> facade.edit(101, 1, 0L, CONTENT, 99);
            case KNOWLEDGE_DRAFT_CREATE -> facade.createDraft(101, 0L, CONTENT, 99);
            case KNOWLEDGE_APPROVE -> facade.approve(101, 1, 0L, 99);
            case KNOWLEDGE_PUBLISH -> facade.publish(101, 1, 0L, 0L, 99);
            case KNOWLEDGE_ARCHIVE -> facade.archive(101, 1, 0L, 0L, 99);
        }
    }
}
