package com.commercefoundry.domainlayer.notification.adapter.out.client;

import com.commercefoundry.domainlayer.notification.adapter.out.client.feign.AdminNotificationRecipientClient;
import com.commercefoundry.domainlayer.notification.adapter.out.client.support.AdminNotificationRecipientInternalResponseSupport;
import com.commercefoundry.domainlayer.notification.application.port.out.AdminNotificationRecipientQueryPort;
import com.commercefoundry.domainlayer.notification.application.port.out.query.AdminNotificationRecipientsQueryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminNotificationRecipientClientAdapter implements AdminNotificationRecipientQueryPort {
    private final AdminNotificationRecipientClient client;
    private final AdminNotificationRecipientInternalResponseSupport support;

    @Override
    public AdminNotificationRecipientsQueryResult findActiveAdmins() {
        return support.call(client);
    }
}
