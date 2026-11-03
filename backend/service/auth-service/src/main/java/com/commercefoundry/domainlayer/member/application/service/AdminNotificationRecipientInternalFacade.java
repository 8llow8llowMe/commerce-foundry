package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response.AdminNotificationRecipientInternalResponse;
import com.commercefoundry.domainlayer.member.adapter.in.internal.presenter.AdminNotificationRecipientInternalPresenter;
import com.commercefoundry.domainlayer.member.application.port.in.AdminNotificationRecipientInternalUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminNotificationRecipientQueryProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminNotificationRecipientInternalFacade implements AdminNotificationRecipientInternalUseCase {

    private final AdminNotificationRecipientQueryProcessor adminNotificationRecipientQueryProcessor;
    private final AdminNotificationRecipientInternalPresenter adminNotificationRecipientInternalPresenter;

    @Override
    @Transactional(readOnly = true)
    public AdminNotificationRecipientInternalResponse list(long afterId, int size) {
        return adminNotificationRecipientInternalPresenter.toResponse(adminNotificationRecipientQueryProcessor.findIds(afterId, size));
    }
}
