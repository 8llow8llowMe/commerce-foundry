package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.port.out.JobDispatchPort;
import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.application.service.processor.JobHandlerProcessor.RegisteredJobHandler;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobRegistration;
import com.commercefoundry.domainlayer.job.domain.model.JobRequest;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 등록. 같은 서비스의 기능 Facade(리포트 내보내기 등)가 이 Processor 를 주입해 부른다 ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙 1) —
 * 주고받는 것은 domain({@link JobRequest} · {@link JobRegistration})이다.
 *
 * <p>트랜잭션을 이 Processor 에 건다 — 기능 Facade 가 자기 트랜잭션 안에서 부르면 그 트랜잭션에 합류해, 업무 변경과 작업 등록이 함께 커밋되거나
 * 함께 롤백된다. 트랜잭션 없이 부르면({@code JobRegisterUseCase}) 여기서 새로 연다. 새로 만든 작업의 실행 신호({@link JobDispatchPort})도 같은 트랜잭션에
 * 쓰인다 — 등록이 롤백되면 신호도 나가지 않는다.
 */
@Component
@RequiredArgsConstructor
public class JobRegistrationProcessor {

    private final JobRepositoryPort jobRepositoryPort;
    private final JobDispatchPort jobDispatchPort;
    private final JobHandlerProcessor jobHandlerProcessor;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Clock clock;

    /**
     * 작업을 QUEUED 로 등록한다. 멱등 키가 있고 같은 종류 · 같은 키의 활성(QUEUED · RUNNING) 작업이 있으면 새로 만들지 않고 그 작업을 돌려준다 —
     * 끝난 작업의 키는 다시 쓸 수 있다. 시도 상한은 핸들러 옵션(없으면 {@code operations.jobs.retry.max-attempts})이다.
     *
     * @return 새로 만든 작업 또는 같은 키의 기존 활성 작업 — {@link JobRegistration#created()} 로 가린다
     * @throws JobException {@code UNKNOWN_JOB_TYPE} 핸들러가 없는 종류, {@code JOB_PAYLOAD_TOO_LARGE} 16KiB 초과,
     *     {@code JOB_DEDUP_CONFLICT} 같은 키가 동시에 등록됐다 — 트랜잭션이 중단돼 여기서 다시 읽지 못한다. 기능 Facade 의 트랜잭션 안에서 불렀다면
     *     이 예외를 잡고 계속하지 말고 요청 전체를 다시 시도한다(다시 시도하면 기존 작업을 받는다)
     */
    @Transactional
    public JobRegistration register(JobRequest request) {
        RegisteredJobHandler handler = jobHandlerProcessor.find(request.type())
            .orElseThrow(() -> new JobException(JobErrorCode.UNKNOWN_JOB_TYPE, request.type()));
        if (Job.payloadBytes(request.payloadJson()) > Job.MAX_PAYLOAD_BYTES) {
            throw new JobException(JobErrorCode.JOB_PAYLOAD_TOO_LARGE);
        }
        if (request.dedupKey() != null) {
            Optional<Job> active = jobRepositoryPort.findActiveByDedupKey(request.type(), request.dedupKey());
            if (active.isPresent()) {
                return new JobRegistration(active.get(), false);
            }
        }
        Instant now = clock.instant();
        Job job = Job.register(
            snowflakeIdGenerator.generateId(), request.type(), request.payloadJson(), request.dedupKey(), request.requestedByMemberId(),
            request.priority(), request.scheduledAt() != null ? request.scheduledAt() : now, handler.options().maxAttempts()
        );
        Job saved = jobRepositoryPort.insert(job);
        jobDispatchPort.dispatch(saved);
        return new JobRegistration(saved, true);
    }

    /** 같은 종류 · 같은 멱등 키의 활성 작업 — 동시 등록에서 진 쪽이 새 트랜잭션으로 이긴 작업을 읽는다. */
    @Transactional(readOnly = true)
    public Optional<Job> findActiveByDedupKey(String type, String dedupKey) {
        return jobRepositoryPort.findActiveByDedupKey(type, dedupKey);
    }
}
