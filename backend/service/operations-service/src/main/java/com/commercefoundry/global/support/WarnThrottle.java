package com.commercefoundry.global.support;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 같은 원인의 warn 을 간격마다 한 번만 남긴다 — 폴러 · heartbeat 처럼 짧은 주기로 도는 일이 DB 장애 같은 같은 원인으로 계속 실패할 때
 * 로그가 주기마다 쌓이지 않게. 첫 실패는 바로 남기고, 간격 안의 같은 원인은 건너뛴 횟수만 세어 다음 warn 에 싣는다.
 */
public final class WarnThrottle {

    private final long intervalNanos;
    private final LongSupplier nanoClock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public WarnThrottle(Duration interval) {
        this(interval, System::nanoTime);
    }

    WarnThrottle(Duration interval, LongSupplier nanoClock) {
        this.intervalNanos = Objects.requireNonNull(interval, "interval").toNanos();
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /**
     * @param cause 원인 키 — 예외 클래스 이름 등. 값(메시지)을 넣지 않는다 — 키마다 창이 생긴다
     * @return 지금 warn 을 남길 차례면 건너뛴 횟수(0 이상), 아니면 -1
     */
    public long tryAcquire(String cause) {
        long now = nanoClock.getAsLong();
        Window window = windows.compute(cause, (key, current) -> current == null || now - current.openedAt() >= intervalNanos
            ? new Window(now, 0, current == null ? 0 : current.suppressed())
            : new Window(current.openedAt(), current.suppressed() + 1, -1));
        return window.reported();
    }

    /**
     * @param suppressed 이 창에서 건너뛴 횟수
     * @param reported 이번 호출의 결과 — 창을 새로 열었으면 앞 창에서 건너뛴 횟수, 건너뛰었으면 -1
     */
    private record Window(long openedAt, long suppressed, long reported) {
    }
}
