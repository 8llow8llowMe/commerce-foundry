package com.commercefoundry.domainlayer.notification.application.service.processor;

import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRecipientPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyAlertLockPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyObservationPort;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyObservation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentAnomalyObservationProcessor {
    private final PaymentAnomalyObservationPort observations;
    private final NotificationRecipientPort recipients;
    private final PaymentAnomalyAlertLockPort lock;

    public void runLocked(Runnable action) { lock.runLocked(action); }
    public List<Long> recipients() { return recipients.findAllActiveAdminIds(); }
    public PaymentAnomalyObservation observe() { return observations.observe(); }
}
