package com.commercefoundry.domainlayer.notification.adapter.in.event;

import com.commercefoundry.domainlayer.notification.application.command.DltAlertCommand;
import com.commercefoundry.domainlayer.notification.application.port.in.DltAlertEventUseCase;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = {"operations.notifications.dlt-alert.enabled", "commerce.messaging.enabled"})
public class DltAlertEventListener {
    private final DltAlertEventUseCase useCase;

    @KafkaListener(topics = "#{@dltAlertTopics}", groupId = "#{@topicNames.consumerGroup('dlt-alerts')}", containerFactory = "dltAlertListenerFactory")
    public void receive(ConsumerRecord<?, ?> record, Acknowledgment acknowledgment) {
        try {
            useCase.receive(new DltAlertCommand(record.topic(), record.partition(), record.offset()));
            acknowledgment.acknowledge();
        } catch (RuntimeException ignored) {
            log.warn("DLT alert processing unavailable; retry scheduled");
            acknowledgment.nack(Duration.ofSeconds(5));
        }
    }
}
