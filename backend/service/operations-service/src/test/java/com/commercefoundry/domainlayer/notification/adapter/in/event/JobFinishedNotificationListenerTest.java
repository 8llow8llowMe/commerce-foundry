package com.commercefoundry.domainlayer.notification.adapter.in.event;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.job.domain.model.JobFinishedEvent;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.notification.application.port.in.NotificationRecordUseCase;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JobFinishedNotificationListenerTest {

    private static final Instant FINISHED_AT = Instant.parse("2026-10-26T01:00:00Z");

    @Mock
    private NotificationRecordUseCase notificationRecordUseCase;

    @Test
    @DisplayName("요청한 어드민의 실패 작업은 주의 알림으로 남기고 커밋 뒤에 민다")
    void recordsAndPushesForRequester() {
        JobFinishedNotificationListener listener = new JobFinishedNotificationListener(notificationRecordUseCase);
        JobFinishedEvent event = new JobFinishedEvent(10L, "EXPORT", JobStatus.FAILED, 7L, "HANDLER_ERROR", FINISHED_AT);

        listener.record(event);
        listener.push(event);

        verify(notificationRecordUseCase).record(argThat(request ->
            request.kind() == NotificationKind.JOB_FAILED
                && request.severity() == NotificationSeverity.WARNING
                && request.recipientMemberId() == 7L
                && request.linkPath().equals("/admin/jobs/10")
                && request.sourceType().equals("JOB")
                && request.sourceId() == 10L
                && request.title().equals("작업이 실패했습니다")));
        verify(notificationRecordUseCase).push("JOB", 10L, NotificationKind.JOB_FAILED);
    }

    @Test
    @DisplayName("요청자가 없는 시스템 작업은 알림을 만들지 않는다")
    void skipsSystemJob() {
        JobFinishedNotificationListener listener = new JobFinishedNotificationListener(notificationRecordUseCase);
        JobFinishedEvent event = new JobFinishedEvent(10L, "EXPORT", JobStatus.SUCCEEDED, null, null, FINISHED_AT);

        listener.record(event);
        listener.push(event);

        verifyNoInteractions(notificationRecordUseCase);
    }
}
