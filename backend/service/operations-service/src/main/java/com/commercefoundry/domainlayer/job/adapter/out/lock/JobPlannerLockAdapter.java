package com.commercefoundry.domainlayer.job.adapter.out.lock;

import com.commercefoundry.domainlayer.job.application.port.out.JobPlannerLockPort;
import com.commercefoundry.global.properties.JobProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor.TaskResult;
import org.springframework.stereotype.Component;

/**
 * 정기 작업 등록의 단일 실행 락. outbox 릴레이와 같은 {@link LockProvider} 빈(ShedLock 테이블, {@code OperationsServiceBeansConfig})을 쓰고 락 이름만 다르다.
 */
@Component
public class JobPlannerLockAdapter implements JobPlannerLockPort {

    /** {@code operations.shedlock.name} 은 64자다. */
    static final String LOCK_NAME = "operations-job-planner";

    private final LockingTaskExecutor lockingTaskExecutor;
    private final Duration lockAtMost;
    private final Clock clock;

    public JobPlannerLockAdapter(LockProvider lockProvider, JobProperties jobProperties, Clock clock) {
        this.lockingTaskExecutor = new DefaultLockingTaskExecutor(lockProvider);
        this.lockAtMost = jobProperties.planner().lockAtMost();
        this.clock = clock;
    }

    @Override
    public <T> Optional<T> runLocked(Supplier<T> action) {
        TaskResult<T> result;
        try {
            result = lockingTaskExecutor.executeWithLock(action::get, new LockConfiguration(clock.instant(), LOCK_NAME, lockAtMost, Duration.ZERO));
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable throwable) {
            // Supplier 는 checked 예외를 던지지 않는다 — ShedLock 시그니처가 Throwable 이라 남는 갈래다
            throw new IllegalStateException("정기 작업 등록 락 안에서 예상 밖 예외가 났습니다.", throwable);
        }
        return result.wasExecuted() ? Optional.ofNullable(result.getResult()) : Optional.empty();
    }
}
