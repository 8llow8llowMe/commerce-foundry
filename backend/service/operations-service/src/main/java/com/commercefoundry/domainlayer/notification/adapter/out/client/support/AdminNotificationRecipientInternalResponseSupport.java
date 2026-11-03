package com.commercefoundry.domainlayer.notification.adapter.out.client.support;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.notification.adapter.out.client.feign.AdminNotificationRecipientClient;
import com.commercefoundry.domainlayer.notification.application.port.out.query.AdminNotificationRecipientsQueryResult;
import com.commercefoundry.global.properties.AnomalyNotificationProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminNotificationRecipientInternalResponseSupport {
    private static final int PAGE_SIZE = 100;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final AnomalyNotificationProperties properties;

    public AdminNotificationRecipientsQueryResult call(AdminNotificationRecipientClient client) {
        try {
            return circuitBreakerRegistry.circuitBreaker("auth-service").executeSupplier(() -> collect(client));
        } catch (RuntimeException exception) {
            // 전송 / 응답 오류의 본문·아이디·cause를 전파하지 않는다. 빈 목록도 실패다.
            throw new IllegalStateException("Admin notification recipients unavailable");
        }
    }

    private AdminNotificationRecipientsQueryResult collect(AdminNotificationRecipientClient client) {
        List<Long> ids = new ArrayList<>();
        long afterId = 0;
        while (true) {
            Response<AdminNotificationRecipientClient.RecipientPage> response = client.find(afterId, PAGE_SIZE);
            if (response == null || response.dataHeader() == null || !response.dataHeader().success()
                || response.dataBody() == null || response.dataBody().memberIds() == null) {
                throw new IllegalStateException("Invalid admin notification recipient response");
            }
            List<?> page = response.dataBody().memberIds();
            if (page.size() > PAGE_SIZE || ids.size() + page.size() > properties.maxRecipients()) {
                throw new IllegalStateException("Invalid admin notification recipient count");
            }
            for (Object value : page) {
                if (!(value instanceof String text)) {
                    throw new IllegalStateException("Invalid admin notification recipient representation");
                }
                long id = Long.parseLong(text);
                if (id <= afterId || !Long.toString(id).equals(text)) {
                    throw new IllegalStateException("Invalid admin notification recipient identity");
                }
                ids.add(id);
                afterId = id;
            }
            if (page.size() < PAGE_SIZE) {
                return new AdminNotificationRecipientsQueryResult(ids);
            }
        }
    }
}
