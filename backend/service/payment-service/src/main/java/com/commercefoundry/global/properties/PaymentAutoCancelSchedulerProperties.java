package com.commercefoundry.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** One execution/discovery gate; nested settings control scheduling budgets only. */
@ConfigurationProperties("payment.auto-cancel")
public record PaymentAutoCancelSchedulerProperties(boolean enabled, Scheduler scheduler) {
    @ConstructorBinding
    public PaymentAutoCancelSchedulerProperties {
        scheduler = scheduler == null ? new Scheduler(null, null, null, null, null) : scheduler;
    }
    public PaymentAutoCancelSchedulerProperties(boolean enabled, Duration initialDelay, Duration fixedDelay, Duration lockAtMostFor,
        Integer pageSize, Integer maxPagesPerLane) {
        this(enabled, new Scheduler(initialDelay, fixedDelay, lockAtMostFor, pageSize, maxPagesPerLane));
    }
    public Duration initialDelay() { return scheduler.initialDelay(); }
    public Duration fixedDelay() { return scheduler.fixedDelay(); }
    public Duration lockAtMostFor() { return scheduler.lockAtMostFor(); }
    public int pageSize() { return scheduler.pageSize(); }
    public int maxPagesPerLane() { return scheduler.maxPagesPerLane(); }

    public record Scheduler(Duration initialDelay, Duration fixedDelay, Duration lockAtMostFor, Integer pageSize, Integer maxPagesPerLane) {
        public Scheduler {
            initialDelay = positive(initialDelay, Duration.ofMinutes(1));
            fixedDelay = positive(fixedDelay, Duration.ofMinutes(1));
            lockAtMostFor = positive(lockAtMostFor, Duration.ofMinutes(15));
            pageSize = pageSize == null ? 5 : pageSize;
            maxPagesPerLane = maxPagesPerLane == null ? 1 : maxPagesPerLane;
            if (pageSize < 1 || pageSize > 50 || maxPagesPerLane < 1 || maxPagesPerLane > 10 || pageSize * maxPagesPerLane > 100) {
                throw new IllegalArgumentException("payment.auto-cancel.scheduler page budgets are outside supported bounds");
            }
            // Nominal budget for ALL lanes: 2 discovery writes (10s each) + 90s pending execution per candidate.
            // Execution includes 3 collision-acquire TXs, evidence/result TXs and at most 3 PG requests.
            // Add 3 cursor reservations (5s each) per page and 60s headroom. This is not a wall-clock guarantee.
            Duration budget = Duration.ofSeconds((2L * 10 + 90) * pageSize * maxPagesPerLane + 3L * 5 * maxPagesPerLane + 60);
            if (lockAtMostFor.compareTo(budget) < 0) {
                throw new IllegalArgumentException("payment.auto-cancel.scheduler lock-at-most-for is shorter than the nominal three-lane work budget");
            }
        }
    }
    private static Duration positive(Duration value, Duration fallback) {
        Duration result = value == null ? fallback : value;
        if (result.isNegative() || result.isZero()) throw new IllegalArgumentException("Automatic cancellation scheduler durations must be positive");
        return result;
    }
}
