package com.commercefoundry.domainlayer.notification.application.service.processor;

import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyAlertPushPort;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** E3b의 커밋 완료 반환 뒤 연결할 전달 경계다. 현재 자동 호출 경로는 없다. */
@Component
@RequiredArgsConstructor
public class PaymentAnomalyAlertPushProcessor {
    private final PaymentAnomalyAlertPushPort push;

    public void enqueue(List<Notification> notifications) {
        if (notifications == null || notifications.isEmpty() || notifications.size() > 5000) {
            throw new IllegalArgumentException("Invalid anomaly push batch");
        }
        push.enqueue(List.copyOf(notifications));
    }
}
