package com.commercefoundry.domainlayer.notification.application.service.processor;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.commercefoundry.domainlayer.notification.application.command.DltAlertCommand;
import com.commercefoundry.domainlayer.notification.application.port.out.DltAlertStatePort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRecipientPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRepositoryPort;
import com.commercefoundry.domainlayer.notification.domain.model.DltAlertState;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import com.commercefoundry.global.properties.DltAlertProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class DltAlertProcessorTest {
    private final DltAlertStatePort states = mock(DltAlertStatePort.class);
    private final NotificationRepositoryPort notifications = mock(NotificationRepositoryPort.class);
    private final Instant now = Instant.parse("2026-11-03T00:00:00Z");
    private final DltAlertProcessor processor = new DltAlertProcessor(mock(NotificationRecipientPort.class), states, notifications,
        mock(NotificationPushPort.class), mock(SnowflakeIdGenerator.class), Clock.fixed(now, ZoneOffset.UTC), new DltAlertProperties(true, Duration.ofHours(1)));

    @Test
    void replayDoesNotAdvanceOrNotify() {
        when(states.lockTopic("topic")).thenReturn(new DltAlertState("topic", null));
        when(states.checkpoint("topic", 0)).thenReturn(5L);
        processor.record(new DltAlertCommand("topic", 0, 5), List.of(7L), NotificationSeverity.WARNING);
        verifyNoInteractions(notifications);
        verify(states, never()).advance(anyString(), anyInt(), anyLong(), any(), anyLong());
    }

    @Test
    void suppressedOffsetAdvancesWatermarkWithoutExtendingWindow() {
        var state = new DltAlertState("topic", now.minusSeconds(3599));
        when(states.lockTopic("topic")).thenReturn(state);
        when(states.checkpoint("topic", 0)).thenReturn(5L);
        processor.record(new DltAlertCommand("topic", 0, 6), List.of(7L), NotificationSeverity.WARNING);
        verifyNoInteractions(notifications);
        verify(states).advance("topic", 0, 6, state, 0);
    }

    @Test
    void zeroRecipientsDoesNotAcquireLockOrAdvance() {
        assertThatThrownBy(() -> processor.record(new DltAlertCommand("topic", 0, 6), List.of(), NotificationSeverity.WARNING))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(states, notifications);
    }
}
