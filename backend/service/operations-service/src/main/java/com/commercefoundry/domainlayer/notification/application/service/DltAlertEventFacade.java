package com.commercefoundry.domainlayer.notification.application.service;

import com.commercefoundry.domainlayer.notification.application.command.DltAlertCommand;
import com.commercefoundry.domainlayer.notification.application.port.in.DltAlertEventUseCase;
import com.commercefoundry.domainlayer.notification.application.service.processor.DltAlertProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import com.commercefoundry.messaging.event.TopicNames;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DltAlertEventFacade implements DltAlertEventUseCase {
    public static final List<String> TOPICS = List.of("operations.job-run.dlt", "order.order-expired.dlt", "order.order-canceled.dlt",
        "payment.payment-approved.dlt", "order.order-paid.dlt", "order.order-payment-rejected.dlt");
    private final DltAlertProcessor processor;
    private final TopicNames topicNames;

    @Override
    public void receive(DltAlertCommand command) {
        if (TOPICS.stream().map(topicNames::qualified).noneMatch(command.topic()::equals)) {
            throw new IllegalArgumentException("Unsupported DLT topic");
        }
        // DLT alert metadata only; see anomaly-notifications.md.
        var recipientIds = processor.recipients();
        var severity = (command.topic().equals(topicNames.qualified("payment.payment-approved.dlt"))
            || command.topic().equals(topicNames.qualified("order.order-paid.dlt"))
            || command.topic().equals(topicNames.qualified("order.order-payment-rejected.dlt")))
            ? NotificationSeverity.CRITICAL : NotificationSeverity.WARNING;
        processor.record(command, recipientIds, severity);
    }
}
