package com.commercefoundry.domainlayer.notification.adapter.out.client;


import com.commercefoundry.domainlayer.notification.adapter.out.client.feign.NotificationRecipientClient;
import com.commercefoundry.domainlayer.notification.adapter.out.client.support.NotificationRecipientInternalResponseSupport;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationErrorCode;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationException;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRecipientPort;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationRecipientAdapter implements NotificationRecipientPort {
    private final NotificationRecipientClient client;
    private final NotificationRecipientInternalResponseSupport support;

    @Override
    public List<Long> findAllActiveAdminIds() {
        List<Long> all = new ArrayList<>();
        Long afterId = null;
        // DLT alert metadata only; see anomaly-notifications.md.
        for (int page = 0; page < 11; page++) {
            List<Long> ids = support.page(client, afterId);
            all.addAll(ids);
            if (all.size() > 1000) {
                break;
            }
            if (ids.size() < 100) {
                if (!all.isEmpty()) {
                    return List.copyOf(all);
                }
                break;
            }
            afterId = ids.getLast();
        }
        throw new NotificationException(NotificationErrorCode.RECIPIENTS_UNAVAILABLE);
    }
}
