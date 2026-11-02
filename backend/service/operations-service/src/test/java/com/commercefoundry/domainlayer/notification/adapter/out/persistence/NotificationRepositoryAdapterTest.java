package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationRequest;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationRepositoryAdapterTest {

    @Mock
    private NotificationRepository repository;

    @InjectMocks
    private NotificationRepositoryAdapter adapter;

    @Test
    void insertUsesNullVersionForNewEntity() {
        Notification notification = newNotification();
        when(repository.saveAndFlush(any(NotificationEntity.class))).thenAnswer(invocation -> {
            NotificationEntity entity = invocation.getArgument(0);
            assertThat(entity.getVersion()).isNull();
            assertThat(entity.getReadAt()).isNull();
            return entity;
        });

        assertThat(adapter.insert(notification)).isEqualTo(notification);
    }

    @Test
    void insertRejectsReadStateInsteadOfMergingIt() {
        assertThatThrownBy(() -> adapter.insert(newNotification().markRead(Instant.now())))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void insertRejectsExistingVersionEvenWhenUnread() {
        Notification notification = newNotification();
        Notification existing = new Notification(
            notification.id(), notification.recipientMemberId(), notification.kind(), notification.severity(), notification.title(),
            notification.linkPath(), notification.sourceType(), notification.sourceId(), null, null, 0L);

        assertThatThrownBy(() -> adapter.insert(existing)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    private Notification newNotification() {
        return Notification.create(1L, new NotificationRequest(
            NotificationKind.JOB_FAILED, NotificationSeverity.WARNING, "작업이 실패했습니다", "/admin/jobs/10", 7L, "JOB", 10L));
    }
}
