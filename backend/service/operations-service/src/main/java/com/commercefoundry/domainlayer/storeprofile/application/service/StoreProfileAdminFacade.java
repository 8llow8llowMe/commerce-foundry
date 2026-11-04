package com.commercefoundry.domainlayer.storeprofile.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.OperationsAdminAuditProcessor;
import com.commercefoundry.domainlayer.storeprofile.adapter.in.admin.dto.response.StoreProfileAdminResponse;
import com.commercefoundry.domainlayer.storeprofile.adapter.in.admin.presenter.StoreProfileAdminPresenter;
import com.commercefoundry.domainlayer.storeprofile.application.command.StoreProfileSaveCommand;
import com.commercefoundry.domainlayer.storeprofile.application.port.in.StoreProfileAdminUseCase;
import com.commercefoundry.domainlayer.storeprofile.application.service.processor.StoreProfileProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StoreProfileAdminFacade implements StoreProfileAdminUseCase {

    private final StoreProfileProcessor processor;

    private final StoreProfileAdminPresenter presenter;

    private final OperationsAdminAuditProcessor auditProcessor;

    @Override
    public StoreProfileAdminResponse get() {
        return presenter.toResponse(processor.get());
    }

    @Override
    public StoreProfileAdminResponse save(Long actorMemberId, StoreProfileSaveCommand command) {
        processor.validate(command);
        // 저장소 트랜잭션의 커밋까지 끝난 뒤 감사한다. 본문·사업자 실정보는 감사에 전달하지 않는다.
        StoreProfileAdminResponse response;
        try {
            response = presenter.toResponse(processor.save(command));
        } catch (RuntimeException exception) {
            auditProcessor.profileSaved(actorMemberId, false);
            throw exception;
        }
        auditProcessor.profileSaved(actorMemberId, true);
        return response;
    }
}
