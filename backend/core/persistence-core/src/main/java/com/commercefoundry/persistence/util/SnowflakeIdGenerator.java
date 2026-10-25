package com.commercefoundry.persistence.util;

import java.time.Instant;
import java.util.Objects;

/**
 * 64비트 Snowflake ID — 부호 1 · 시각 41 · datacenter 5 · worker 5 · 시퀀스 12 비트.
 *
 * <p>응답에서는 String 으로 내린다 ({@code coding-conventions.md} §7-1). JavaScript Number 는 2^53 을 넘으면 값이 바뀐다.
 */
public class SnowflakeIdGenerator {

    /** 2026-01-01T00:00:00Z. 41비트 시각 칸으로 약 69년을 쓴다. 운영 데이터가 생긴 뒤에는 바꾸지 않는다. */
    static final long EPOCH = 1767225600000L;

    private static final long DATACENTER_ID_BITS = 5L;
    private static final long WORKER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    private final long datacenterId;
    private final long workerId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(long datacenterId, long workerId) {
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException("datacenterId 는 0~%d 이어야 합니다: %d".formatted(MAX_DATACENTER_ID, datacenterId));
        }
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException("workerId 는 0~%d 이어야 합니다: %d".formatted(MAX_WORKER_ID, workerId));
        }

        this.datacenterId = datacenterId;
        this.workerId = workerId;
    }

    public synchronized long generateId() {
        long timestamp = currentTimeMillis();

        // 시계가 뒤로 가면 이미 발급한 ID 와 겹칠 수 있다. 조용히 넘기지 않는다
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException("시스템 시계가 %dms 뒤로 이동했습니다".formatted(lastTimestamp - timestamp));
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
            | (datacenterId << DATACENTER_ID_SHIFT)
            | (workerId << WORKER_ID_SHIFT)
            | sequence;
    }

    /**
     * 이 시각에 발급될 수 있는 가장 작은 아이디(datacenter · worker · 시퀀스 0).
     * 이 값보다 작은 아이디는 더 이전 밀리초다. 기준 시각과 같은 밀리초의 아이디는 여기에 포함되지 않는다.
     * 기준이 에폭 이전이면 0 — 그 시각의 아이디는 없다.
     */
    public static long lowestIdAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        long timestamp = instant.toEpochMilli();
        if (timestamp <= EPOCH) {
            return 0L;
        }
        long delta = timestamp - EPOCH;
        if (delta >= (1L << 41)) {
            throw new IllegalArgumentException("Snowflake 시각 범위를 넘었습니다");
        }
        return delta << TIMESTAMP_SHIFT;
    }

    /** 테스트가 시계를 고정할 수 있게 열어 둔다. */
    long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }
}
