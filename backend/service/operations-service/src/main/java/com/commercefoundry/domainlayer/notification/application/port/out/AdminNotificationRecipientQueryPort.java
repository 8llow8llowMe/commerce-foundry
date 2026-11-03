package com.commercefoundry.domainlayer.notification.application.port.out;

import com.commercefoundry.domainlayer.notification.application.port.out.query.AdminNotificationRecipientsQueryResult;

public interface AdminNotificationRecipientQueryPort {
    AdminNotificationRecipientsQueryResult findActiveAdmins();
}
