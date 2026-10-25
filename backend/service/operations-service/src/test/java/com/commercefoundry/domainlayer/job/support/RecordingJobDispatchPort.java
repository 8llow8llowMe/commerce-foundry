package com.commercefoundry.domainlayer.job.support;

import com.commercefoundry.domainlayer.job.application.port.out.JobDispatchPort;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Processor · Facade 단위 테스트용 실행 신호 기록기 — 낸 신호를 순서대로 모은다. outbox · Kafka 왕복은 {@code JobWorkerFlowTest} 가 본다. */
public class RecordingJobDispatchPort implements JobDispatchPort {

    private final List<Signal> signals = new CopyOnWriteArrayList<>();

    @Override
    public void dispatch(Job job, Instant availableAt) {
        signals.add(new Signal(job, availableAt));
    }

    public List<Job> dispatched() {
        return signals.stream().map(Signal::job).toList();
    }

    public List<Job> dispatchedOf(long jobId) {
        return dispatched().stream().filter(job -> job.id() == jobId).toList();
    }

    public List<Signal> signalsOf(long jobId) {
        return signals.stream().filter(signal -> signal.job().id() == jobId).toList();
    }

    public record Signal(Job job, Instant availableAt) {
    }
}
