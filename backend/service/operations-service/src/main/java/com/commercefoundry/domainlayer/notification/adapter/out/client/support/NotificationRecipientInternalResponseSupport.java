package com.commercefoundry.domainlayer.notification.adapter.out.client.support;


import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.notification.adapter.out.client.feign.NotificationRecipientClient;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationErrorCode;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationRecipientInternalResponseSupport {
    private final CircuitBreakerRegistry registry;

    public List<Long> page(NotificationRecipientClient client, Long afterId) {
        try {
            return registry.circuitBreaker("auth-service").executeSupplier(() -> normalize(client.page(afterId, 100), afterId));
        } catch (RuntimeException exception) {
            // DLT alert metadata only; see anomaly-notifications.md.
            throw new NotificationException(NotificationErrorCode.RECIPIENTS_UNAVAILABLE);
        }
    }

    private List<Long> normalize(Response<NotificationRecipientClient.RecipientPage> response, Long afterId) {
        if (response == null || response.dataHeader() == null || !response.dataHeader().success() || response.dataBody() == null
            || response.dataBody().memberIds() == null || response.dataBody().memberIds().size() > 100) {
            throw new IllegalArgumentException("Invalid recipients");
        }
        long previous = afterId == null ? 0 : afterId;
        var result = new java.util.ArrayList<Long>();
        for (String raw : response.dataBody().memberIds()) {
            long id = Long.parseLong(raw);
            if (id <= previous || !Long.toString(id).equals(raw)) {
                throw new IllegalArgumentException("Invalid recipients");
            }
            result.add(id);
            previous = id;
        }
        return List.copyOf(result);
    }
}
