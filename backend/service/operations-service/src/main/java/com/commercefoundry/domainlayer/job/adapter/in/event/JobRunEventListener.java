package com.commercefoundry.domainlayer.job.adapter.in.event;

import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.info.JobDispatchResult;
import com.commercefoundry.domainlayer.job.application.port.in.JobWorkerUseCase;
import com.commercefoundry.global.messaging.JobRunMessage;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.properties.MessagingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 작업 실행 신호 컨슈머 ({@code architecture-guide.md} §10). 그룹 {@code <ns>.operations.job-runner} 하나를 모든 워커 인스턴스가 나눠 받는다 — 신호 한 건은
 * 한 인스턴스에만 간다. {@code operations.jobs.worker.enabled=false} 면 이 빈을 만들지 않는다 — 그 인스턴스는 등록만 하고 실행은 다른 인스턴스가 한다.
 * 메시징이 꺼져 있어도({@code commerce.messaging.enabled=false}) 만들지 않는다 — 컨테이너 자동 시작만 끄면 컨텍스트 재시작({@code Lifecycle#start})이
 * 리스너 레지스트리를 통해 컨테이너를 띄운다.
 *
 * <p>컨슈머 스레드는 인스턴스당 하나다. 가져가 실행 스레드에 넘기기까지만 하고 바로 확인(ack)한다 — 실행이 끝나기를 기다리지 않는다. 상태 · 중복 · 취소 ·
 * lease 의 정본은 {@code operations.job} 이라 inbox(처리 기록)를 두지 않는다 — 중복 신호는 가져가기가 시도 번호로 거른다.
 * 이 인스턴스의 전체 실행 칸이 찼거나 종료 중이면 {@code nack(Duration)} 으로 리밸런스 없이 늦춰 다시 받는다(ack 모드 MANUAL_IMMEDIATE —
 * {@code JobRunListenerConfig}). nack 은 컨슈머의 모든 파티션을 그동안 멈춘다 — 종류별 칸만 찼거나 예약 시각 전이면 워커가 같은 시도의 신호를
 * outbox 로 늦게 다시 내고 이 신호는 확인한다(다른 종류 신호를 막지 않는다).
 *
 * <p>읽을 수 없는 봉투 · 모르는 새 버전 · 아이디 형식 오류 · 이 인스턴스에 없는 종류는 다시 받아도 같아 바로 DLT 다. DB 오류 같은 일시 오류는 컨슈머 안에서
 * 재시도한 뒤 DLT 다({@code commerce.messaging.retry.*}). DLT 로 간 신호의 작업은 QUEUED 로 남는다 — 사람이 DLT 를 다시 보내 실행한다(README 운영 전제).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = {"operations.jobs.worker.enabled", MessagingProperties.ENABLED})
public class JobRunEventListener {

    /** 컨슈머 그룹 목적 토막 — {@code <ns>.operations.job-runner}. */
    public static final String CONSUMER_PURPOSE = "job-runner";

    private final JobWorkerUseCase jobWorkerUseCase;
    private final EventEnvelopeCodec eventEnvelopeCodec;

    @KafkaListener(
        id = "job-runner",
        topics = "#{@topicNames.topic(T(com.commercefoundry.global.messaging.JobRunMessage).EVENT_TYPE)}",
        groupId = "#{@topicNames.consumerGroup(T(com.commercefoundry.domainlayer.job.adapter.in.event.JobRunEventListener).CONSUMER_PURPOSE)}",
        concurrency = "1",
        containerPostProcessor = "jobRunContainerPostProcessor"
    )
    public void onJobRun(String value, Acknowledgment acknowledgment) {
        EventEnvelope envelope = eventEnvelopeCodec.decode(value);
        eventEnvelopeCodec.requireVersion(envelope, JobRunMessage.SCHEMA_VERSION);
        JobRunMessage message = eventEnvelopeCodec.payload(envelope, JobRunMessage.class);
        long jobId = parseJobId(message, envelope);
        if (message.type() == null) {
            throw new NonRetryableEventException("작업 종류가 없는 실행 신호입니다. eventId=" + envelope.eventId());
        }
        JobDispatchResult result;
        try {
            result = jobWorkerUseCase.dispatch(jobId, message.attempt(), message.type());
        } catch (JobException exception) {
            if (exception.getErrorCode() == JobErrorCode.UNKNOWN_JOB_TYPE) {
                throw new NonRetryableEventException("이 인스턴스에 핸들러가 없는 작업 종류입니다. eventId=" + envelope.eventId() + ", jobId=" + jobId, exception);
            }
            throw exception;
        }
        if (result.outcome() == JobDispatchResult.Outcome.REDELIVER) {
            acknowledgment.nack(result.redeliverAfter());
            return;
        }
        acknowledgment.acknowledge();
    }

    private static long parseJobId(JobRunMessage message, EventEnvelope envelope) {
        try {
            return Long.parseLong(message.jobId());
        } catch (NumberFormatException exception) {
            throw new NonRetryableEventException("작업 아이디 형식이 아닌 실행 신호입니다. eventId=" + envelope.eventId());
        }
    }
}
