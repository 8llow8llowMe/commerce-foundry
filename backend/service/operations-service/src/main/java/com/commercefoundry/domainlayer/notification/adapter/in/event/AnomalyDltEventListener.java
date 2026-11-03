package com.commercefoundry.domainlayer.notification.adapter.in.event;

import com.commercefoundry.domainlayer.notification.application.command.AnomalyRecordCommand;
import com.commercefoundry.domainlayer.notification.application.port.in.AnomalyNotificationUseCase;
import com.commercefoundry.domainlayer.notification.domain.model.AnomalySource;
import com.commercefoundry.messaging.properties.MessagingProperties;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("operations.notifications.anomaly.enabled")
public class AnomalyDltEventListener {
    private final AnomalyNotificationUseCase useCase;
    private final MessagingProperties messagingProperties;

    @KafkaListener(id = "anomaly-dlt", containerFactory = "anomalyDltListenerContainerFactory",
        groupId = "${commerce.messaging.namespace}.operations.anomaly-dlt", topics = {
            "${commerce.messaging.namespace}.payment.payment-approved.dlt",
            "${commerce.messaging.namespace}.order.order-expired.dlt",
            "${commerce.messaging.namespace}.order.order-canceled.dlt"
        })
    public void receive(ConsumerRecord<byte[], byte[]> record) {
        for (AnomalySource source : AnomalySource.values()) {
            if ((messagingProperties.namespace() + "." + source.topicSuffix()).equals(record.topic())) {
                try {
                    useCase.process(new AnomalyRecordCommand(source, record.topic(), record.partition(), record.offset()));
                    return;
                } catch (RuntimeException exception) {
                    // Kafka 래퍼 / 프레임워크 로그에도 수신자·전송·DB 원문 예외를 넘기지 않는다.
                    throw new IllegalStateException("Anomaly DLT processing failed");
                }
            }
        }
        throw new IllegalArgumentException("Unsupported anomaly DLT source");
    }
}
