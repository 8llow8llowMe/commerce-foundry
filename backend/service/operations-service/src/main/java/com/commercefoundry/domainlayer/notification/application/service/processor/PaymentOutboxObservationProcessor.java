package com.commercefoundry.domainlayer.notification.application.service.processor;

import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRecipientPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentOutboxAlertLockPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentOutboxObservationPort;
import com.commercefoundry.domainlayer.notification.application.info.PaymentOutboxObservationInfo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentOutboxObservationProcessor {
    private final NotificationRecipientPort recipients;
    private final PaymentOutboxObservationPort observations;
    private final PaymentOutboxAlertLockPort lock;
    public void runLocked(Runnable action) { lock.runLocked(action); }
    public List<Long> recipients() { return recipients.findAllActiveAdminIds(); }
    public PaymentOutboxObservationInfo observe() {
        var result = observations.observe();
        return new PaymentOutboxObservationInfo(result.observedAt(), result.readyUnpublishedCount(), result.oldestReadyAvailableAt());
    }
}
