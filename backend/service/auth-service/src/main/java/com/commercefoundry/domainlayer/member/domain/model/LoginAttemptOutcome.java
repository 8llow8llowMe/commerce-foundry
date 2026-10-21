package com.commercefoundry.domainlayer.member.domain.model;

import java.time.Duration;
import java.util.Objects;

/**
 * 시도 1회를 센 결과 — 저장소가 더하기와 같은 자리에서 읽은 값이다 (#66).
 *
 * @param count      이번 시도를 포함한 창 안의 시도 수. 1 이상
 * @param retryAfter 창이 끝날 때까지 남은 시간. 0 이상 — 창은 첫 시도에만 걸려 이어지는 시도가 늘리지 않는다
 */
public record LoginAttemptOutcome(long count, Duration retryAfter) {

    public LoginAttemptOutcome {
        Objects.requireNonNull(retryAfter, "retryAfter");
        if (count < 1) {
            throw new IllegalArgumentException("count 는 1 이상이어야 한다");
        }
        if (retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter 는 0 이상이어야 한다");
        }
    }

    /** 이번 시도가 허용 횟수를 넘겼다 — {@code maxAttempts} 번째 시도까지는 넘기지 않고, 그다음부터 넘긴다. */
    public boolean exceeds(int maxAttempts) {
        return count > maxAttempts;
    }
}
