package com.commercefoundry.domainlayer.notification.adapter.in.event;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.commercefoundry.domainlayer.notification.application.command.DltAlertCommand;
import com.commercefoundry.domainlayer.notification.application.port.in.DltAlertEventUseCase;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class DltAlertEventListenerTest {
    @Test
    void readsOnlyMetadataAndCommitsAfterSuccess() {
        var useCase = mock(DltAlertEventUseCase.class);
        var ack = mock(Acknowledgment.class);
        ConsumerRecord<String, String> record = mock(ConsumerRecord.class);
        when(record.topic()).thenReturn("test.payment.payment-approved.dlt");
        when(record.partition()).thenReturn(2);
        when(record.offset()).thenReturn(19L);
        new DltAlertEventListener(useCase).receive(record, ack);
        var order = inOrder(useCase, ack);
        order.verify(useCase).receive(new DltAlertCommand("test.payment.payment-approved.dlt", 2, 19));
        order.verify(ack).acknowledge();
        verify(record, never()).key();
        verify(record, never()).value();
        verify(record, never()).headers();
    }

    @Test
    void failureNacksWithoutAcknowledgingOrPropagatingSensitiveException() {
        var useCase = mock(DltAlertEventUseCase.class);
        var ack = mock(Acknowledgment.class);
        doThrow(new IllegalStateException("sensitive-payload")).when(useCase).receive(any());
        new DltAlertEventListener(useCase).receive(new ConsumerRecord<>("test.operations.job-run.dlt", 0, 1, "private-key", "private-body"), ack);
        verify(ack, never()).acknowledge();
        verify(ack).nack(Duration.ofSeconds(5));
    }
}
