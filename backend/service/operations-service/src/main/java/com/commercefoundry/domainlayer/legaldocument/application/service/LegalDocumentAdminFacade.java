package com.commercefoundry.domainlayer.legaldocument.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.OperationsAdminAuditProcessor;
import com.commercefoundry.domainlayer.legaldocument.adapter.in.admin.dto.response.LegalDocumentAdminResponses;
import com.commercefoundry.domainlayer.legaldocument.adapter.in.admin.presenter.LegalDocumentAdminPresenter;
import com.commercefoundry.domainlayer.legaldocument.application.command.LegalDocumentCommands;
import com.commercefoundry.domainlayer.legaldocument.application.port.in.LegalDocumentAdminUseCase;
import com.commercefoundry.domainlayer.legaldocument.application.service.processor.LegalDocumentProcessor;
import com.commercefoundry.domainlayer.legaldocument.application.service.processor.LegalDocumentTemplateProcessor;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LegalDocumentAdminFacade implements LegalDocumentAdminUseCase {
    private final LegalDocumentProcessor processor;
    private final LegalDocumentTemplateProcessor templates;
    private final LegalDocumentAdminPresenter presenter;
    private final OperationsAdminAuditProcessor audit;

    @Override
    public List<LegalDocumentAdminResponses.Summary> list() {
        return processor.list().stream().map(presenter::summary).toList();
    }

    @Override
    public LegalDocumentAdminResponses.Detail detail(String type, String locale) {
        return presenter.detail(processor.detail(type, locale));
    }

    @Override
    public PageResponse<LegalDocumentAdminResponses.Revision> revisions(String type, String locale, int page, int size) {
        return processor.revisions(type, locale, page, size).map(presenter::revision);
    }

    @Override
    public LegalDocumentAdminResponses.Template template(String type, String locale) {
        return presenter.template(templates.get(processor.key(type, locale)));
    }

    @Override
    public LegalDocumentAdminResponses.Detail save(long actorMemberId, LegalDocumentCommands.SaveDraft command) {
        LegalDocumentAdminResponses.Detail response;
        try {
            response = presenter.detail(processor.save(actorMemberId, command));
        } catch (RuntimeException exception) {
            audit.legalDraftSaved(actorMemberId, command.type(), command.locale(), false);
            throw exception;
        }
        audit.legalDraftSaved(actorMemberId, command.type(), command.locale(), true);
        return response;
    }

    @Override
    public LegalDocumentAdminResponses.Detail publish(long actorMemberId, LegalDocumentCommands.Publish command) {
        LegalDocumentAdminResponses.Detail response;
        try {
            response = presenter.detail(processor.publish(actorMemberId, command));
        } catch (RuntimeException exception) {
            audit.legalPublished(actorMemberId, command.type(), command.locale(), false);
            throw exception;
        }
        audit.legalPublished(actorMemberId, command.type(), command.locale(), true);
        return response;
    }
}
