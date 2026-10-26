package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationRepositoryAdapterTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-26T00:00:00Z");
    private static final Instant READ_AT = Instant.parse("2026-10-26T01:00:00Z");

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationRepositoryAdapter notificationRepositoryAdapter;

    @Test
    @DisplayName("읽음 저장은 merge 가 생성 시각을 비워도 읽어 둔 시각을 응답에 남긴다")
    void saveKeepsCreatedAtWhenMergeDropsIt() {
        Notification notification = new Notification(
            1L, 7L, NotificationKind.JOB_FAILED, NotificationSeverity.WARNING, "작업이 실패했습니다", "/admin/jobs/10", "JOB", 10L, READ_AT, CREATED_AT, 0L);
        when(notificationRepository.saveAndFlush(any(NotificationEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Notification saved = notificationRepositoryAdapter.save(notification);

        assertThat(saved.createdAt()).isEqualTo(CREATED_AT);
        assertThat(saved.readAt()).isEqualTo(READ_AT);
    }
}
