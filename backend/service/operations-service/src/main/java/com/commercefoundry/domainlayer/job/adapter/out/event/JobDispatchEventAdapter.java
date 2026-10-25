package com.commercefoundry.domainlayer.job.adapter.out.event;

import com.commercefoundry.domainlayer.job.application.port.out.JobDispatchPort;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.global.messaging.JobRunMessage;
import com.commercefoundry.messaging.publish.DomainEvent;
import com.commercefoundry.messaging.publish.EventPublisher;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 작업 실행 신호를 outbox 에 쓴다 ({@code coding-conventions.md} §12). 키는 작업 아이디 — 같은 작업의 신호는 같은 파티션에 순서대로 간다.
 * 발행 가능 시각까지 발행을 미룬다(outbox {@code available_at}) — Kafka 에는 지연 전달이 없다.
 */
@Component
@RequiredArgsConstructor
public class JobDispatchEventAdapter implements JobDispatchPort {

    private final EventPublisher eventPublisher;

    @Override
    public void dispatch(Job job, Instant availableAt) {
        if (job.status() != JobStatus.QUEUED) {
            throw new IllegalArgumentException("QUEUED 작업만 실행 신호를 냅니다. jobId=" + job.id() + ", status=" + job.status());
        }
        String jobId = String.valueOf(job.id());
        JobRunMessage message = new JobRunMessage(jobId, job.type(), job.attempt(), job.scheduledAt());
        eventPublisher.publish(
            new DomainEvent(JobRunMessage.AGGREGATE_TYPE, jobId, JobRunMessage.EVENT_TYPE, JobRunMessage.SCHEMA_VERSION, message),
            availableAt
        );
    }
}
