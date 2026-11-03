package com.commercefoundry.domainlayer.notification.application.port.out;

import java.util.List;

public interface NotificationRecipientPort {
    List<Long> findAllActiveAdminIds();
}
