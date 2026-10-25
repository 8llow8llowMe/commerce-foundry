package com.commercefoundry.persistence.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {

    @Test
    @DisplayName("한 밀리초의 시퀀스(4096개)를 다 써도 다음 밀리초로 넘어가 겹치지 않고 커지기만 한다")
    void uniqueAndIncreasingAcrossSequenceWrap() {
        SnowflakeIdGenerator generator = new FixedClockGenerator(SnowflakeIdGenerator.EPOCH + 1_000L);

        Set<Long> ids = new HashSet<>();
        long previous = -1L;
        for (int i = 0; i < 5_000; i++) {
            long id = generator.generateId();
            assertThat(id).isGreaterThan(previous);
            ids.add(id);
            previous = id;
        }

        assertThat(ids).hasSize(5_000);
    }

    @Test
    @DisplayName("datacenterId · workerId 가 ID 에 들어가 인스턴스끼리 겹치지 않는다")
    void differentWorkersNeverCollide() {
        long now = SnowflakeIdGenerator.EPOCH + 1_000L;

        long first = new FixedClockGenerator(now, 1, 1).generateId();
        long second = new FixedClockGenerator(now, 1, 2).generateId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("시계가 뒤로 가면 발급하지 않는다")
    void clockMovedBackwards() {
        FixedClockGenerator generator = new FixedClockGenerator(SnowflakeIdGenerator.EPOCH + 1_000L);
        generator.generateId();
        generator.now = SnowflakeIdGenerator.EPOCH + 999L;

        assertThatThrownBy(generator::generateId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("lowestIdAt 은 그 밀리초의 첫 아이디다 — 이전 밀리초는 더 작고, 에폭 이전이면 0 이다")
    void lowestIdAtIsTheFirstIdOfThatMillisecond() {
        long timestamp = SnowflakeIdGenerator.EPOCH + 5_000L;
        SnowflakeIdGenerator generator = new FixedClockGenerator(timestamp);

        assertThat(generator.generateId()).isEqualTo(SnowflakeIdGenerator.lowestIdAt(Instant.ofEpochMilli(timestamp)));
        assertThat(SnowflakeIdGenerator.lowestIdAt(Instant.ofEpochMilli(timestamp)))
            .isGreaterThan(SnowflakeIdGenerator.lowestIdAt(Instant.ofEpochMilli(timestamp - 1)));
        assertThat(SnowflakeIdGenerator.lowestIdAt(Instant.ofEpochMilli(SnowflakeIdGenerator.EPOCH))).isZero();
        assertThat(SnowflakeIdGenerator.lowestIdAt(Instant.EPOCH)).isZero();
        assertThatThrownBy(() -> SnowflakeIdGenerator.lowestIdAt(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SnowflakeIdGenerator.lowestIdAt(Instant.ofEpochMilli(SnowflakeIdGenerator.EPOCH + (1L << 41))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("datacenterId · workerId 는 0~31 만 허용한다")
    void rejectsOutOfRangeIds() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(32, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeIdGenerator(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 처음 4096번은 같은 밀리초를 돌려준다. 그 뒤로는 부를 때마다 1ms 씩 민다 —
     * 시퀀스가 감겨 다음 밀리초를 기다리는 경로가 끝나도록.
     */
    private static final class FixedClockGenerator extends SnowflakeIdGenerator {

        private long now;
        private int calls;

        FixedClockGenerator(long now) {
            this(now, 0, 0);
        }

        FixedClockGenerator(long now, long datacenterId, long workerId) {
            super(datacenterId, workerId);
            this.now = now;
        }

        @Override
        long currentTimeMillis() {
            if (++calls > 4096) {
                now++;
            }
            return now;
        }
    }
}
