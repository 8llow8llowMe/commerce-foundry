package com.commercefoundry.domainlayer.notification.adapter.in.event;

import com.commercefoundry.domainlayer.job.domain.model.JobFinishedEvent;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.notification.application.port.in.NotificationRecordUseCase;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationRequest;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationSeverity;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 작업이 끝나면 요청한 어드민의 알림을 같은 트랜잭션에 남기고, 커밋 뒤에 스트림으로 민다 (#213, {@code architecture-guide.md} §10).
 * 시스템 등록(요청자 없음)은 알림을 만들지 않는다. 제목은 고정 문구라 작업 입력 · 오류 원문을 싣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class JobFinishedNotificationListener {

    static final String SOURCE_TYPE = "JOB";

    private final NotificationRecordUseCase notificationRecordUseCase;

    @EventListener
    public void record(JobFinishedEvent event) {
        toRequest(event).ifPresent(notificationRecordUseCase::record);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void push(JobFinishedEvent event) {
        toRequest(event).ifPresent(request -> notificationRecordUseCase.push(request.sourceType(), request.sourceId(), request.kind()));
    }

    private Optional<NotificationRequest> toRequest(JobFinishedEvent event) {
        Long recipientMemberId = event.requestedByMemberId();
        if (recipientMemberId == null) {
            return Optional.empty();
        }
        NotificationKind kind = kindOf(event.status());
        if (kind == null) {
            return Optional.empty();
        }
        NotificationSeverity severity = kind == NotificationKind.JOB_FAILED ? NotificationSeverity.WARNING : NotificationSeverity.INFO;
        return Optional.of(new NotificationRequest(
            kind,
            severity,
            titleOf(kind),
            "/admin/jobs/" + event.jobId(),
            recipientMemberId,
            SOURCE_TYPE,
            event.jobId()
        ));
    }

    private static NotificationKind kindOf(JobStatus status) {
        return switch (status) {
            case SUCCEEDED -> NotificationKind.JOB_SUCCEEDED;
            case FAILED -> NotificationKind.JOB_FAILED;
            case CANCELLED -> NotificationKind.JOB_CANCELLED;
            case QUEUED, RUNNING -> null;
        };
    }

    private static String titleOf(NotificationKind kind) {
        return switch (kind) {
            case JOB_SUCCEEDED -> "작업이 끝났습니다";
            case JOB_FAILED -> "작업이 실패했습니다";
            case JOB_CANCELLED -> "작업이 취소되었습니다";
            default -> "알림";
        };
    }
}
