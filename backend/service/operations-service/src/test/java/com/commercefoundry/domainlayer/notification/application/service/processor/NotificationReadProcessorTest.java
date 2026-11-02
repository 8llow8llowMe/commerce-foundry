package com.commercefoundry.domainlayer.notification.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.notification.application.exception.NotificationErrorCode;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationException;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRepositoryPort;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationReadProcessorTest {

    private static final Instant CREATED = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant NOW = CREATED.plusSeconds(3600);

    @Mock
    private NotificationRepositoryPort repository;

    @Mock
    private NotificationPushPort push;

    @InjectMocks
    private NotificationAdminProcessor processor;

    @Test
    void losingReadReturnsWinnersStoredTimestampAndCreatedAt() {
        Notification unread = notification(7L, null);
        Notification winner = notification(7L, NOW.minusSeconds(1));
        when(repository.findById(1L)).thenReturn(Optional.of(unread), Optional.of(winner));
        when(repository.markReadIfUnread(1L, 7L, NOW)).thenReturn(0);

        assertThat(processor.markRead(1L, 7L, NOW)).isEqualTo(winner);
        verify(repository).markReadIfUnread(1L, 7L, NOW);
    }

    @Test
    void successfulUpdateAlsoReturnsActualStoredRow() {
        Notification winner = notification(7L, NOW);
        when(repository.findById(1L)).thenReturn(Optional.of(notification(7L, null)), Optional.of(winner));
        when(repository.markReadIfUnread(1L, 7L, NOW)).thenReturn(1);

        assertThat(processor.markRead(1L, 7L, NOW)).isEqualTo(winner);
    }

    @Test
    void alreadyReadDoesNotWriteAgain() {
        Notification read = notification(7L, NOW.minusSeconds(1));
        when(repository.findById(1L)).thenReturn(Optional.of(read));

        assertThat(processor.markRead(1L, 7L, NOW)).isSameAs(read);
        verify(repository, never()).markReadIfUnread(1L, 7L, NOW);
    }

    @Test
    void otherOwnersAndMissingRowsHaveTheSameNotFound() {
        when(repository.findById(1L)).thenReturn(Optional.of(notification(8L, null)), Optional.empty());

        assertNotFound();
        assertNotFound();
        verify(repository, never()).markReadIfUnread(1L, 7L, NOW);
    }

    @Test
    void deletionBetweenReadAndUpdateIsNotFoundAndDoesNotReinsert() {
        when(repository.findById(1L)).thenReturn(Optional.of(notification(7L, null)), Optional.empty());

        assertNotFound();
        verify(repository).markReadIfUnread(1L, 7L, NOW);
    }

    private void assertNotFound() {
        assertThatThrownBy(() -> processor.markRead(1L, 7L, NOW))
            .isInstanceOfSatisfying(NotificationException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(NotificationErrorCode.NOT_FOUND));
    }

    private Notification notification(long recipient, Instant readAt) {
        return new Notification(1L, recipient, NotificationKind.JOB_FAILED, NotificationSeverity.WARNING,
            "작업이 실패했습니다", "/admin/jobs/10", "JOB", 10L, readAt, CREATED, 0L);
    }
}
