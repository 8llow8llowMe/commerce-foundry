package com.commercefoundry.domainlayer.storefooter.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.OperationsAdminAuditProcessor;
import com.commercefoundry.domainlayer.storefooter.adapter.in.admin.dto.response.StoreFooterAdminResponses;
import com.commercefoundry.domainlayer.storefooter.adapter.in.admin.presenter.StoreFooterAdminPresenter;
import com.commercefoundry.domainlayer.storefooter.application.command.StoreFooterCommands;
import com.commercefoundry.domainlayer.storefooter.application.port.in.StoreFooterAdminUseCase;
import com.commercefoundry.domainlayer.storefooter.application.service.processor.StoreFooterProcessor;
import com.commercefoundry.persistence.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StoreFooterAdminFacade implements StoreFooterAdminUseCase {
    private final StoreFooterProcessor processor;
    private final StoreFooterAdminPresenter presenter;
    private final OperationsAdminAuditProcessor audit;

    @Override
    public StoreFooterAdminResponses.Detail detail() {
        return presenter.detail(processor.detail());
    }

    @Override
    public PageResponse<StoreFooterAdminResponses.Revision> revisions(int page, int size) {
        return processor.revisions(page, size).map(presenter::revision);
    }

    @Override
    public StoreFooterAdminResponses.Detail save(long actorMemberId, StoreFooterCommands.SaveDraft command) {
        StoreFooterAdminResponses.Detail response;
        try {
            response = presenter.detail(processor.save(actorMemberId, command));
        } catch (RuntimeException exception) {
            audit.footerDraftSaved(actorMemberId, false);
            throw exception;
        }
        audit.footerDraftSaved(actorMemberId, true);
        return response;
    }

    @Override
    public StoreFooterAdminResponses.Detail publish(long actorMemberId, StoreFooterCommands.Publish command) {
        StoreFooterAdminResponses.Detail response;
        try {
            response = presenter.detail(processor.publish(actorMemberId, command));
        } catch (RuntimeException exception) {
            audit.footerPublished(actorMemberId, false);
            throw exception;
        }
        audit.footerPublished(actorMemberId, true);
        return response;
    }
}
