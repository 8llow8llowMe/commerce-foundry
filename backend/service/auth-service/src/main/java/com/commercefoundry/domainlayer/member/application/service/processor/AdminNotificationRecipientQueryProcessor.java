package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.port.out.AdminNotificationRecipientQueryPort;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminNotificationRecipientQueryProcessor {

    private final AdminNotificationRecipientQueryPort adminNotificationRecipientQueryPort;

    public List<Long> findIds(long afterId, int size) {
        return adminNotificationRecipientQueryPort.findActiveAdminIdsAfter(afterId, size);
    }
}
