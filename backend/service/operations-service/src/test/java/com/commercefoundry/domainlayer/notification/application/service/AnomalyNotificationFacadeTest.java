package com.commercefoundry.domainlayer.notification.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.notification.application.command.AnomalyRecordCommand;
import com.commercefoundry.domainlayer.notification.application.model.AnomalyRecipientSnapshot;
import com.commercefoundry.domainlayer.notification.application.service.processor.AnomalyNotificationProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.AnomalySource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

class AnomalyNotificationFacadeTest {
    private final AnomalyNotificationProcessor processor = mock(AnomalyNotificationProcessor.class);
    private final AnomalyNotificationFacade facade = new AnomalyNotificationFacade(processor);
    private final AnomalyRecordCommand command = new AnomalyRecordCommand(AnomalySource.PAYMENT_APPROVED,
        "commerce-foundry.test.payment.payment-approved.dlt", 0, 1);
    private final AnomalyRecipientSnapshot recipients = new AnomalyRecipientSnapshot(List.of(7L, 8L));

    @Test
    void knownDuplicateDoesNotDependOnAuthAvailability() {
        when(processor.alreadyProcessed(command)).thenReturn(true);
        facade.process(command);
        verify(processor, never()).findRecipients();
        verify(processor, never()).record(any(), any());
    }

    @Test
    void optimisticConflictsRetryOnlyThreeFreshCallsAndReuseRecipients() {
        when(processor.findRecipients()).thenReturn(recipients);
        doThrow(new OptimisticLockingFailureException("sensitive-cause"), new OptimisticLockingFailureException("sensitive-cause"))
            .doNothing().when(processor).record(command, recipients);
        facade.process(command);
        verify(processor, times(1)).findRecipients();
        verify(processor, times(3)).record(command, recipients);
    }

    @Test
    void unexpectedIntegrityViolationIsNotRetried() {
        when(processor.findRecipients()).thenReturn(recipients);
        doThrow(new DataIntegrityViolationException("fixed-error")).when(processor).record(command, recipients);
        assertThatThrownBy(() -> facade.process(command)).isInstanceOf(DataIntegrityViolationException.class);
        verify(processor, times(1)).record(command, recipients);
    }

    @Test
    void exhaustedConflictsAndAuthFailureHaveNoPartialRecordCall() {
        when(processor.findRecipients()).thenThrow(new IllegalStateException("fixed-error"));
        assertThatThrownBy(() -> facade.process(command)).isInstanceOf(IllegalStateException.class);
        verify(processor, never()).record(any(), any());
        doReturn(recipients).when(processor).findRecipients();
        doThrow(new OptimisticLockingFailureException("sensitive-cause")).when(processor).record(command, recipients);
        assertThatThrownBy(() -> facade.process(command)).hasMessage("Anomaly notification concurrency retries exhausted").hasNoCause();
        verify(processor, times(3)).record(command, recipients);
    }
}
