package com.commercefoundry.domainlayer.notification.adapter.in.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.global.properties.AnomalyNotificationProperties;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.MessageListenerContainer;

class AnomalyDltErrorHandlerTest {
    @Test
    void interleavedPartitionFailuresStopAtThirdAttemptWithoutAckOrRecovery() {
        var handler = new AnomalyDltErrorHandler(new AnomalyNotificationProperties(true, Duration.ofHours(1), Duration.ofDays(8), 1000, 3, Duration.ZERO));
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        ConsumerRecord<?, ?> a = new ConsumerRecord<>("commerce-foundry.test.payment.payment-approved.dlt", 0, 4, "private-key", "private-body");
        ConsumerRecord<?, ?> b = new ConsumerRecord<>(a.topic(), 1, 9, "private-key", "private-body");
        for (ConsumerRecord<?, ?> record : List.of(a, b, a, b)) {
            handler.handleRemaining(new IllegalStateException("private-cause"), List.of(record), consumer, container);
        }
        verify(container, never()).stopAbnormally(any());
        handler.handleRemaining(new IllegalStateException("private-cause"), List.of(a), consumer, container);
        verify(container).stopAbnormally(any());
        verify(consumer, times(3)).seek(new TopicPartition(a.topic(), 0), 4L);
        verify(consumer, never()).commitSync();
        assertThat(handler.isAckAfterHandle()).isFalse();
        assertThat(handler.seeksAfterHandling()).isTrue();
        handler.succeeded(a);
        handler.handleRemaining(new IllegalStateException(), List.of(a), consumer, container);
        verify(container, times(1)).stopAbnormally(any());
        handler.onPartitionsAssigned(consumer, List.of(new TopicPartition(a.topic(), 0)), () -> { });
        handler.handleRemaining(new IllegalStateException(), List.of(a), consumer, container);
        verify(container, times(1)).stopAbnormally(any());
        handler.clearThreadState();
    }
}
