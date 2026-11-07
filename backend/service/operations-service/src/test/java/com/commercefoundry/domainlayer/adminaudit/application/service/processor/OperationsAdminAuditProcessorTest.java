package com.commercefoundry.domainlayer.adminaudit.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.adminaudit.application.model.OperationsAdminAuditAction;
import com.commercefoundry.domainlayer.legaldocument.adapter.in.admin.controller.LegalDocumentAdminController;
import com.commercefoundry.domainlayer.legaldocument.adapter.in.admin.presenter.LegalDocumentAdminPresenter;
import com.commercefoundry.domainlayer.legaldocument.application.command.LegalDocumentCommands;
import com.commercefoundry.domainlayer.legaldocument.application.service.LegalDocumentAdminFacade;
import com.commercefoundry.domainlayer.legaldocument.application.service.processor.LegalDocumentProcessor;
import com.commercefoundry.domainlayer.legaldocument.application.service.processor.LegalDocumentTemplateProcessor;
import com.commercefoundry.domainlayer.storeprofile.adapter.in.admin.controller.StoreProfileAdminController;
import com.commercefoundry.domainlayer.storeprofile.adapter.in.admin.presenter.StoreProfileAdminPresenter;
import com.commercefoundry.domainlayer.storeprofile.application.command.StoreProfileSaveCommand;
import com.commercefoundry.domainlayer.storeprofile.application.service.StoreProfileAdminFacade;
import com.commercefoundry.domainlayer.storeprofile.application.service.processor.StoreProfileProcessor;
import com.commercefoundry.domainlayer.storefooter.adapter.in.admin.controller.StoreFooterAdminController;
import com.commercefoundry.domainlayer.storefooter.adapter.in.admin.presenter.StoreFooterAdminPresenter;
import com.commercefoundry.domainlayer.storefooter.application.command.StoreFooterCommands;
import com.commercefoundry.domainlayer.storefooter.application.service.StoreFooterAdminFacade;
import com.commercefoundry.domainlayer.storefooter.application.service.processor.StoreFooterProcessor;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.security.audit.AdminAudited;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;

class OperationsAdminAuditProcessorTest {
    private final Logger diagnostic = mock(Logger.class);
    private final OperationsAdminAuditProcessor audit = new OperationsAdminAuditProcessor(diagnostic);
    private final RuntimeException auditFailure = new IllegalArgumentException("WRITER_SENTINEL");
    private final RuntimeException diagnosticFailure = new UnsupportedOperationException("DIAGNOSTIC_SENTINEL");
    private final RuntimeException original = new IllegalStateException("EXCEPTION_SENTINEL", new RuntimeException("CAUSE_SENTINEL"));

    @Test
    void enumMatchesOnlyMigratedControllersNotAllOperationsActions() {
        var declared = Stream.of(StoreProfileAdminController.class, LegalDocumentAdminController.class, StoreFooterAdminController.class)
            .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
            .filter(method -> method.isAnnotationPresent(AdminAudited.class))
            .map(method -> method.getAnnotation(AdminAudited.class).action()).toList();
        assertThat(declared).containsExactlyInAnyOrderElementsOf(Arrays.stream(OperationsAdminAuditAction.values()).map(Enum::name).toList());
        assertThat(declared).hasSize(5).doesNotContain("JOB_CANCEL", "CAMPAIGN_RECIPIENT_PREVIEW", "JOB_RESULT_DOWNLOAD");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void footerActionsUseFixedTargetAndAuditFailureCannotEscape(boolean publish) {
        String action = publish ? "STORE_FOOTER_PUBLISH" : "STORE_FOOTER_DRAFT_SAVE";
        doThrow(diagnosticFailure).when(diagnostic).warn(anyString(), any(), any(), any());
        try (var writer = mockStatic(AdminAuditLog.class)) {
            writer.when(() -> AdminAuditLog.write(action, 7L, "store-footer:1", true)).thenThrow(auditFailure);
            assertThatCode(() -> {
                if (publish) {
                    audit.footerPublished(7L, true);
                } else {
                    audit.footerDraftSaved(7L, true);
                }
            }).doesNotThrowAnyException();
            writer.verify(() -> AdminAuditLog.write(action, 7L, "store-footer:1", true), times(1));
            writer.verifyNoMoreInteractions();
            verify(diagnostic).warn("Admin audit unavailable action={} actorMemberId={} exceptionType={}",
                action, 7L, IllegalArgumentException.class.getName());
            verifyNoMoreInteractions(diagnostic);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void footerPresenterFailurePreservesOriginalExceptionAndNeverWritesSuccess(boolean publish) {
        var processor = mock(StoreFooterProcessor.class);
        var presenter = mock(StoreFooterAdminPresenter.class);
        when(presenter.detail(any())).thenThrow(original);
        var facade = new StoreFooterAdminFacade(processor, presenter, audit);
        String action = publish ? "STORE_FOOTER_PUBLISH" : "STORE_FOOTER_DRAFT_SAVE";
        doThrow(diagnosticFailure).when(diagnostic).warn(anyString(), any(), any(), any());
        try (var writer = mockStatic(AdminAuditLog.class)) {
            writer.when(() -> AdminAuditLog.write(action, 7L, "store-footer:1", false)).thenThrow(auditFailure);
            assertThatThrownBy(() -> {
                if (publish) {
                    facade.publish(7L, new StoreFooterCommands.Publish(0L, 1));
                } else {
                    facade.save(7L, new StoreFooterCommands.SaveDraft(null, null));
                }
            }).isSameAs(original);
            writer.verify(() -> AdminAuditLog.write(action, 7L, "store-footer:1", false), times(1));
            writer.verifyNoMoreInteractions();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void writerAndDiagnosticFailuresAreIsolatedWithSafeDiagnosticArguments(boolean diagnosticFails) {
        if (diagnosticFails) {
            doThrow(diagnosticFailure).when(diagnostic).warn(anyString(), any(), any(), any());
        }
        try (var writer = mockStatic(AdminAuditLog.class)) {
            writer.when(() -> AdminAuditLog.write("STORE_PROFILE_UPDATE", 7L, "store-profile:1", true)).thenThrow(auditFailure);
            assertThatCode(() -> audit.profileSaved(7L, true)).doesNotThrowAnyException();
            writer.verify(() -> AdminAuditLog.write("STORE_PROFILE_UPDATE", 7L, "store-profile:1", true), times(1));
            writer.verifyNoMoreInteractions();
            verify(diagnostic).warn("Admin audit unavailable action={} actorMemberId={} exceptionType={}",
                "STORE_PROFILE_UPDATE", 7L, IllegalArgumentException.class.getName());
            verifyNoMoreInteractions(diagnostic);
        }
    }

    @Test
    void profileValidationStaysUnauditedAndOriginalExceptionIdentitySurvives() {
        var processor = mock(StoreProfileProcessor.class);
        var command = new StoreProfileSaveCommand(null, null);
        var facade = new StoreProfileAdminFacade(processor, mock(StoreProfileAdminPresenter.class), audit);
        doThrow(original).when(processor).validate(command);
        try (var writer = mockStatic(AdminAuditLog.class)) {
            assertThatThrownBy(() -> facade.save(7L, command)).isSameAs(original);
            writer.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void profileBusinessAndPresenterFailureKeepIdentityAndWriteOnlyFailure(boolean presenterFails) {
        var processor = mock(StoreProfileProcessor.class);
        var presenter = mock(StoreProfileAdminPresenter.class);
        var command = new StoreProfileSaveCommand(null, null);
        if (presenterFails) {
            when(presenter.toResponse(any())).thenThrow(original);
        } else {
            when(processor.save(command)).thenThrow(original);
        }
        var facade = new StoreProfileAdminFacade(processor, presenter, audit);
        doThrow(diagnosticFailure).when(diagnostic).warn(anyString(), any(), any(), any());
        try (var writer = mockStatic(AdminAuditLog.class)) {
            writer.when(() -> AdminAuditLog.write("STORE_PROFILE_UPDATE", 7L, "store-profile:1", false)).thenThrow(auditFailure);
            assertThatThrownBy(() -> facade.save(7L, command)).isSameAs(original);
            writer.verify(() -> AdminAuditLog.write("STORE_PROFILE_UPDATE", 7L, "store-profile:1", false), times(1));
            writer.verifyNoMoreInteractions();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legalSaveAndPublishPresenterFailureKeepIdentityAndNeverWriteSuccess(boolean publish) {
        var processor = mock(LegalDocumentProcessor.class);
        var presenter = mock(LegalDocumentAdminPresenter.class);
        when(presenter.detail(any())).thenThrow(original);
        var facade = new LegalDocumentAdminFacade(processor, mock(LegalDocumentTemplateProcessor.class), presenter, audit);
        String action = publish ? "LEGAL_DOCUMENT_PUBLISH" : "LEGAL_DOCUMENT_DRAFT_SAVE";
        doThrow(diagnosticFailure).when(diagnostic).warn(anyString(), any(), any(), any());
        try (var writer = mockStatic(AdminAuditLog.class)) {
            writer.when(() -> AdminAuditLog.target("legal-document", "terms-ko")).thenReturn("legal-document:terms-ko");
            writer.when(() -> AdminAuditLog.write(action, 7L, "legal-document:terms-ko", false)).thenThrow(auditFailure);
            assertThatThrownBy(() -> {
                if (publish) {
                    facade.publish(7L, new LegalDocumentCommands.Publish("TERMS", "ko", 0L, 1, true));
                } else {
                    facade.save(7L, new LegalDocumentCommands.SaveDraft("TERMS", "ko", null, "title", "body"));
                }
            }).isSameAs(original);
            writer.verify(() -> AdminAuditLog.write(action, 7L, "legal-document:terms-ko", false), times(1));
            writer.verify(() -> AdminAuditLog.write(action, 7L, "legal-document:terms-ko", true), never());
        }
    }
}
