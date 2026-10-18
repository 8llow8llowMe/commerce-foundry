package com.commercefoundry.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 실제 시계 + 테스트가 정한 만큼 앞당긴 시계. 흐름 테스트가 며칠 · 몇 주 뒤의 재발급(family 절대 수명)을 기다리지 않고 본다.
 * 테스트가 끝나면 {@link #reset()} 으로 되돌린다 — 컨텍스트를 같이 쓰는 다른 테스트에 번지지 않게.
 */
public class AdjustableClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

    public void advance(Duration amount) {
        offset = offset.plus(amount);
    }

    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("UTC 하나만 쓴다");
    }
}
