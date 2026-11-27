package com.commercefoundry.domainlayer.storenavigation.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.OperationsAdminAuditProcessor;
import com.commercefoundry.domainlayer.storenavigation.adapter.in.admin.dto.response.StoreNavigationAdminResponses;
import com.commercefoundry.domainlayer.storenavigation.adapter.in.admin.presenter.StoreNavigationAdminPresenter;
import com.commercefoundry.domainlayer.storenavigation.application.command.StoreNavigationCommands;
import com.commercefoundry.domainlayer.storenavigation.application.port.in.StoreNavigationAdminUseCase;
import com.commercefoundry.domainlayer.storenavigation.application.service.processor.StoreNavigationProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 쓰기에 @Transactional 을 걸지 않는다 — 포트가 독립 트랜잭션으로 커밋하고, 감사는 커밋·실패 뒤 따로 남겨 감사 실패가 업무 결과를 바꾸지 않게 한다. */
@Service
@RequiredArgsConstructor
public class StoreNavigationAdminFacade implements StoreNavigationAdminUseCase {
    private final StoreNavigationProcessor processor;
    private final StoreNavigationAdminPresenter presenter;
    private final OperationsAdminAuditProcessor audit;

    @Override
    public StoreNavigationAdminResponses.Detail detail() {
        return presenter.detail(processor.detail());
    }

    @Override
    public StoreNavigationAdminResponses.Detail save(long actorMemberId, StoreNavigationCommands.SaveDraft command) {
        StoreNavigationAdminResponses.Detail response;
        try {
            response = presenter.detail(processor.save(actorMemberId, command));
        } catch (RuntimeException exception) {
            audit.navigationDraftSaved(actorMemberId, false);
            throw exception;
        }
        audit.navigationDraftSaved(actorMemberId, true);
        return response;
    }

    @Override
    public StoreNavigationAdminResponses.Detail publish(long actorMemberId, StoreNavigationCommands.Publish command) {
        StoreNavigationAdminResponses.Detail response;
        try {
            response = presenter.detail(processor.publish(actorMemberId, command));
        } catch (RuntimeException exception) {
            audit.navigationPublished(actorMemberId, false);
            throw exception;
        }
        audit.navigationPublished(actorMemberId, true);
        return response;
    }
}
