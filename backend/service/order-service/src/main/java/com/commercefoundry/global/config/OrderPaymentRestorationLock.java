package com.commercefoundry.global.config;

import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbc.JdbcLockProvider;

/** 결제 반영 복원 전용 배치 락. 행의 lease와 완료 CAS는 업무 실행 단계가 담당한다. */
public final class OrderPaymentRestorationLock {

    static final String NAME = "order-payment-restoration";
    private final JdbcLockProvider provider;
    private final Clock clock;

    OrderPaymentRestorationLock(DataSource dataSource, Clock clock) {
        this.provider = new JdbcLockProvider(dataSource, "orders.shedlock");
        this.clock = clock;
    }

    /** 락을 얻은 경우에만 작업을 실행하고, 작업 실패에도 락을 반납한다. */
    public boolean runIfAcquired(Duration lockAtMostFor, Runnable work) {
        var acquired = provider.lock(new LockConfiguration(clock.instant(), NAME, lockAtMostFor, Duration.ZERO));
        if (acquired.isEmpty()) {
            return false;
        }
        SimpleLock lock = acquired.get();
        try {
            work.run();
            return true;
        } finally {
            lock.unlock();
        }
    }
}
