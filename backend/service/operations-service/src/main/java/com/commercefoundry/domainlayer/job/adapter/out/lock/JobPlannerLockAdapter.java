package com.commercefoundry.domainlayer.job.adapter.out.lock;

import com.commercefoundry.domainlayer.job.application.port.out.JobPlannerLockPort;
import com.commercefoundry.global.properties.JobProperties;
import com.commercefoundry.messaging.properties.MessagingProperties;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbc.JdbcLockProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 정기 작업 등록의 단일 실행 락. outbox 릴레이와 같은 ShedLock 테이블을 쓰고 락 이름만 다르다 — {@code LockProvider} 빈이 있으면 그것을 쓴다.
 */
@Component
public class JobPlannerLockAdapter implements JobPlannerLockPort {

    /** {@code operations.shedlock.name} 은 64자다. */
    static final String LOCK_NAME = "operations-job-planner";

    private final LockingTaskExecutor lockingTaskExecutor;
    private final Duration lockAtMost;
    private final Clock clock;

    public JobPlannerLockAdapter(
        ObjectProvider<LockProvider> lockProvider, ObjectProvider<DataSource> dataSource, MessagingProperties messagingProperties,
        JobProperties jobProperties, Clock clock
    ) {
        this.lockingTaskExecutor = new DefaultLockingTaskExecutor(lockProvider.getIfAvailable(() -> jdbcLockProvider(dataSource, messagingProperties)));
        this.lockAtMost = jobProperties.planner().lockAtMost();
        this.clock = clock;
    }

    @Override
    public void runLocked(Runnable action) {
        lockingTaskExecutor.executeWithLock((Runnable) action, new LockConfiguration(clock.instant(), LOCK_NAME, lockAtMost, Duration.ZERO));
    }

    /** 서비스가 LockProvider 빈을 따로 두지 않았으면 서비스 DataSource 의 ShedLock 테이블을 쓴다. */
    private static LockProvider jdbcLockProvider(ObjectProvider<DataSource> dataSource, MessagingProperties messagingProperties) {
        DataSource lockDataSource = dataSource.getIfAvailable();
        if (lockDataSource == null) {
            throw new IllegalStateException("정기 작업 플래너의 단일 실행 락에 DataSource 가 필요합니다.");
        }
        return new JdbcLockProvider(lockDataSource, messagingProperties.outbox().relay().lockTable());
    }
}
