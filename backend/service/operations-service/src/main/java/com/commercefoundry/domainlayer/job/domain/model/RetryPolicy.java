package com.commercefoundry.domainlayer.job.domain.model;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 재시도 지연 — 지수 백오프 + jitter. {@code n} 번째 시도가 실패하면 다음 시도까지
 * {@code min(maxDelay, baseDelay × multiplier^(n−1)) × (1 ± jitterRatio)} 를 기다린다.
 * 횟수 상한은 작업마다 {@link Job#maxAttempts()} 가 정한다.
 *
 * <p>jitter 는 같은 원인(대상 서비스 장애)으로 한꺼번에 실패한 작업들이 같은 시각에 다시 몰리지 않게 흩는다.
 *
 * @param baseDelay 첫 실패 뒤 지연
 * @param multiplier 실패마다 곱하는 배수 (1 이상)
 * @param maxDelay jitter 를 더하기 전 지연 상한
 * @param jitterRatio 0 이상 1 미만. 0.2 면 지연을 ±20% 안에서 흔든다
 */
public record RetryPolicy(Duration baseDelay, double multiplier, Duration maxDelay, double jitterRatio) {

    public RetryPolicy {
        Objects.requireNonNull(baseDelay, "baseDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay 는 0 보다 커야 합니다: " + baseDelay);
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay 는 baseDelay 이상이어야 합니다: " + maxDelay);
        }
        if (!(multiplier >= 1.0) || Double.isInfinite(multiplier)) {
            throw new IllegalArgumentException("multiplier 는 1 이상이어야 합니다: " + multiplier);
        }
        if (!(jitterRatio >= 0.0 && jitterRatio < 1.0)) {
            throw new IllegalArgumentException("jitterRatio 는 0 이상 1 미만이어야 합니다: " + jitterRatio);
        }
    }

    /** {@code attempt} 번째 시도가 실패한 뒤의 지연. jitter 는 스레드 난수로 고른다. */
    public Duration delayAfter(int attempt) {
        return delayAfter(attempt, ThreadLocalRandom.current().nextDouble());
    }

    /**
     * @param attempt 실패한 시도 번호 (1 부터)
     * @param unitRandom {@code [0, 1)} 난수. 0 이면 {@code −jitterRatio}, 0.5 면 jitter 없음, 1 에 가까우면 {@code +jitterRatio} 쪽이다
     */
    public Duration delayAfter(int attempt, double unitRandom) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt 는 1 이상이어야 합니다: " + attempt);
        }
        if (!(unitRandom >= 0.0 && unitRandom < 1.0)) {
            throw new IllegalArgumentException("unitRandom 은 0 이상 1 미만이어야 합니다: " + unitRandom);
        }
        // 지수가 커지면 double 이 무한대로 가므로 상한과 먼저 비교한다 — 상한 이하인 값만 long 으로 바꾼다
        double exponential = baseDelay.toMillis() * Math.pow(multiplier, attempt - 1);
        double capped = Math.min(maxDelay.toMillis(), exponential);
        double jittered = capped * (1.0 + jitterRatio * (2.0 * unitRandom - 1.0));
        return Duration.ofMillis(Math.max(0L, Math.round(jittered)));
    }
}
