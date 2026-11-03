package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response.AdminNotificationRecipientInternalResponse;

public interface AdminNotificationRecipientInternalUseCase {

    AdminNotificationRecipientInternalResponse list(long afterId, int size);
}
