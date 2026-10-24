package com.commercefoundry.domainlayer.job.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class JobTest {

    private static final Instant NOW = Instant.parse("2026-10-24T09:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final RetryPolicy NO_JITTER = new RetryPolicy(Duration.ofSeconds(30), 4, Duration.ofMinutes(30), 0.0);
    private static final String WORKER = "operations-service:host:1:abcd1234";
    private static final String OTHER_WORKER = "operations-service:host:2:efgh5678";

    @Nested
    @DisplayName("등록")
    class Register {

        @Test
        @DisplayName("QUEUED · 시도 0 · 버전 없음으로 만들고, 활성 멱등 키는 멱등 키와 같다")
        void registersQueued() {
            Job job = queued(3, "REPORT@2026-10-24");

            assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
            assertThat(job.attempt()).isZero();
            assertThat(job.version()).isNull();
            assertThat(job.activeDedupKey()).isEqualTo("REPORT@2026-10-24");
            assertThat(job.isClaimable(NOW)).isTrue();
            assertThat(job.isClaimable(NOW.minusSeconds(1))).as("예약 시각 전").isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"report", "REPORT-EXPORT", ""})
        @DisplayName("종류는 [A-Z0-9_]{1,64} 다")
        void rejectsInvalidType(String type) {
            assertThatThrownBy(() -> Job.register(1L, type, "{}", null, null, 0, NOW, 3)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("payload 는 16KiB(UTF-8 바이트) 이하다 — 한글은 3바이트로 센다")
        void rejectsLargePayload() {
            String atLimit = "a".repeat(Job.MAX_PAYLOAD_BYTES);
            String overByKorean = "가".repeat(Job.MAX_PAYLOAD_BYTES / 3 + 1);

            assertThat(Job.register(1L, "REPORT", atLimit, null, null, 0, NOW, 3).payloadJson()).hasSize(Job.MAX_PAYLOAD_BYTES);
            assertThatThrownBy(() -> Job.register(1L, "REPORT", overByKorean, null, null, 0, NOW, 3)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("시도 상한은 1 이상이다")
        void rejectsNonPositiveMaxAttempts() {
            assertThatThrownBy(() -> Job.register(1L, "REPORT", "{}", null, null, 0, NOW, 0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("claim")
    class Claim {

        @Test
        @DisplayName("QUEUED → RUNNING — 시도 +1, 워커 · lease · 시작 시각을 채운다")
        void queuedToRunning() {
            Job running = queued(3, null).claim(WORKER, NOW, LEASE);

            assertThat(running.status()).isEqualTo(JobStatus.RUNNING);
            assertThat(running.attempt()).isEqualTo(1);
            assertThat(running.workerId()).isEqualTo(WORKER);
            assertThat(running.leaseExpiresAt()).isEqualTo(NOW.plus(LEASE));
            assertThat(running.startedAt()).isEqualTo(NOW);
            assertThat(running.isOwnedBy(WORKER, 1)).isTrue();
            assertThat(running.isOwnedBy(WORKER, 2)).as("다른 시도").isFalse();
            assertThat(running.isOwnedBy(OTHER_WORKER, 1)).as("다른 워커").isFalse();
        }

        @Test
        @DisplayName("예약 시각 전 QUEUED · lease 가 유효한 RUNNING 은 가져갈 수 없다")
        void rejectsNotClaimable() {
            Job scheduled = Job.register(1L, "REPORT", "{}", null, null, 0, NOW.plusSeconds(60), 3);
            Job running = queued(3, null).claim(WORKER, NOW, LEASE);

            assertThatThrownBy(() -> scheduled.claim(WORKER, NOW, LEASE)).isInstanceOf(JobTransitionException.class);
            assertThatThrownBy(() -> running.claim(OTHER_WORKER, NOW.plus(LEASE), LEASE))
                .as("lease 만료 시각과 같으면 아직 유효하다")
                .isInstanceOf(JobTransitionException.class);
        }

        @Test
        @DisplayName("lease 가 만료된 RUNNING 은 새 워커가 다음 시도로 회수한다 — 이전 워커는 더 이상 주인이 아니다")
        void reclaimsExpiredLease() {
            Job running = queued(3, null).claim(WORKER, NOW, LEASE);
            Instant later = NOW.plus(LEASE).plusSeconds(1);

            Job reclaimed = running.claim(OTHER_WORKER, later, LEASE);

            assertThat(reclaimed.status()).isEqualTo(JobStatus.RUNNING);
            assertThat(reclaimed.attempt()).isEqualTo(2);
            assertThat(reclaimed.workerId()).isEqualTo(OTHER_WORKER);
            assertThat(reclaimed.errorCode()).isEqualTo(Job.LEASE_EXPIRED);
            assertThat(reclaimed.isOwnedBy(WORKER, 1)).isFalse();
        }

        @Test
        @DisplayName("lease 가 만료됐는데 시도 상한이면 FAILED(LEASE_EXPIRED)로 끝내고 활성 멱등 키를 비운다")
        void failsExpiredLeaseAtLimit() {
            Job running = queued(1, "KEY").claim(WORKER, NOW, LEASE);

            Job failed = running.claim(OTHER_WORKER, NOW.plus(LEASE).plusSeconds(1), LEASE);

            assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
            assertThat(failed.errorCode()).isEqualTo(Job.LEASE_EXPIRED);
            assertThat(failed.attempt()).isEqualTo(1);
            assertThat(failed.leaseExpiresAt()).isNull();
            assertThat(failed.finishedAt()).isEqualTo(NOW.plus(LEASE).plusSeconds(1));
            assertThat(failed.activeDedupKey()).isNull();
        }

        @Test
        @DisplayName("취소 요청을 받은 채 lease 가 만료되면 CANCELLED 로 끝낸다")
        void cancelsExpiredLeaseWithCancelRequest() {
            Job running = queued(3, null).claim(WORKER, NOW, LEASE).requestCancel(7L, NOW);

            assertThat(running.claim(OTHER_WORKER, NOW.plus(LEASE).plusSeconds(1), LEASE).status()).isEqualTo(JobStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("succeed · fail")
    class Finish {

        @Test
        @DisplayName("성공 — SUCCEEDED, 결과 · 진행률 100, 이전 시도 오류를 지우고 활성 멱등 키를 비운다")
        void succeed() {
            Job running = queued(3, "KEY").claim(WORKER, NOW, LEASE).fail("E", "x", true, NOW, NO_JITTER).claim(WORKER, NOW.plusSeconds(60), LEASE);

            Job succeeded = running.succeed("{\"rows\":3}", NOW.plusSeconds(90));

            assertThat(succeeded.status()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(succeeded.resultJson()).isEqualTo("{\"rows\":3}");
            assertThat(succeeded.progressPercent()).isEqualTo(100);
            assertThat(succeeded.errorCode()).isNull();
            assertThat(succeeded.leaseExpiresAt()).isNull();
            assertThat(succeeded.activeDedupKey()).isNull();
            assertThat(succeeded.dedupKey()).isEqualTo("KEY");
        }

        @Test
        @DisplayName("재시도할 수 있고 시도가 남았으면 QUEUED — 예약 시각은 now + 백오프, 워커 · lease 를 비우고 오류를 남긴다")
        void retries() {
            Job running = queued(3, "KEY").claim(WORKER, NOW, LEASE);

            Job retrying = running.fail("SOURCE_DOWN", "대상 서비스 503", true, NOW, NO_JITTER);

            assertThat(retrying.status()).isEqualTo(JobStatus.QUEUED);
            assertThat(retrying.scheduledAt()).isEqualTo(NOW.plusSeconds(30));
            assertThat(retrying.attempt()).isEqualTo(1);
            assertThat(retrying.workerId()).isNull();
            assertThat(retrying.leaseExpiresAt()).isNull();
            assertThat(retrying.errorCode()).isEqualTo("SOURCE_DOWN");
            assertThat(retrying.activeDedupKey()).as("재시도 대기도 활성이다").isEqualTo("KEY");

            Job second = retrying.claim(WORKER, NOW.plusSeconds(30), LEASE).fail("SOURCE_DOWN", null, true, NOW.plusSeconds(40), NO_JITTER);
            assertThat(second.scheduledAt()).as("두 번째 실패는 30s × 4").isEqualTo(NOW.plusSeconds(40).plus(Duration.ofMinutes(2)));
        }

        @Test
        @DisplayName("시도 상한이면 재시도할 수 있어도 FAILED")
        void failsAtLimit() {
            Job running = queued(1, null).claim(WORKER, NOW, LEASE);

            Job failed = running.fail("SOURCE_DOWN", null, true, NOW, NO_JITTER);

            assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
            assertThat(failed.finishedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("재시도할 수 없는 실패는 시도가 남아도 바로 FAILED")
        void failsNonRetryable() {
            assertThat(queued(3, null).claim(WORKER, NOW, LEASE).fail("INVALID_INPUT", null, false, NOW, NO_JITTER).status()).isEqualTo(JobStatus.FAILED);
        }

        @Test
        @DisplayName("취소 요청을 받은 뒤 실패하면 재시도하지 않고 CANCELLED")
        void failAfterCancelRequestCancels() {
            Job running = queued(3, null).claim(WORKER, NOW, LEASE).requestCancel(7L, NOW);

            assertThat(running.fail("SOURCE_DOWN", null, true, NOW, NO_JITTER).status()).isEqualTo(JobStatus.CANCELLED);
        }

        @Test
        @DisplayName("오류 메시지는 500자에서 자른다")
        void truncatesErrorMessage() {
            Job failed = queued(1, null).claim(WORKER, NOW, LEASE).fail("E", "x".repeat(600), false, NOW, NO_JITTER);

            assertThat(failed.errorMessage()).hasSize(Job.ERROR_MESSAGE_MAX_LENGTH);
        }

        @ParameterizedTest
        @EnumSource(value = JobStatus.class, names = {"QUEUED", "SUCCEEDED", "FAILED", "CANCELLED"})
        @DisplayName("실행 중이 아니면 succeed · fail · release 를 거절한다")
        void rejectsWhenNotRunning(JobStatus status) {
            Job job = inStatus(status);

            assertThatThrownBy(() -> job.succeed(null, NOW)).isInstanceOf(JobTransitionException.class);
            assertThatThrownBy(() -> job.fail("E", null, true, NOW, NO_JITTER)).isInstanceOf(JobTransitionException.class);
            assertThatThrownBy(() -> job.release(NOW)).isInstanceOf(JobTransitionException.class);
        }
    }

    @Nested
    @DisplayName("취소 · 돌려놓기")
    class CancelAndRelease {

        @Test
        @DisplayName("QUEUED 에 취소 요청 → 바로 CANCELLED, 요청자를 남기고 활성 멱등 키를 비운다")
        void requestCancelQueued() {
            Job cancelled = queued(3, "KEY").requestCancel(7L, NOW);

            assertThat(cancelled.status()).isEqualTo(JobStatus.CANCELLED);
            assertThat(cancelled.cancelRequested()).isTrue();
            assertThat(cancelled.cancelRequestedByMemberId()).isEqualTo(7L);
            assertThat(cancelled.activeDedupKey()).isNull();
        }

        @Test
        @DisplayName("RUNNING 에 취소 요청 → 플래그만 세우고 RUNNING 이다. 다시 요청해도 그대로다(멱등)")
        void requestCancelRunning() {
            Job flagged = queued(3, null).claim(WORKER, NOW, LEASE).requestCancel(7L, NOW);

            assertThat(flagged.status()).isEqualTo(JobStatus.RUNNING);
            assertThat(flagged.cancelRequested()).isTrue();
            assertThat(flagged.requestCancel(8L, NOW)).isSameAs(flagged);
            assertThat(flagged.cancel(NOW).status()).as("워커가 멈춘 뒤 끝낸다").isEqualTo(JobStatus.CANCELLED);
        }

        @ParameterizedTest
        @EnumSource(value = JobStatus.class, names = {"SUCCEEDED", "FAILED", "CANCELLED"})
        @DisplayName("끝난 작업은 취소 · 취소 요청 · claim 을 거절한다")
        void rejectsFinished(JobStatus status) {
            Job job = inStatus(status);

            assertThatThrownBy(() -> job.requestCancel(7L, NOW))
                .isInstanceOfSatisfying(JobTransitionException.class, exception -> {
                    assertThat(exception.getCurrentStatus()).isEqualTo(status);
                    assertThat(exception.getAttemptedAction()).isEqualTo(JobTransitionException.Action.REQUEST_CANCEL);
                });
            assertThatThrownBy(() -> job.cancel(NOW)).isInstanceOf(JobTransitionException.class);
            assertThatThrownBy(() -> job.claim(WORKER, NOW.plus(Duration.ofDays(1)), LEASE)).isInstanceOf(JobTransitionException.class);
        }

        @Test
        @DisplayName("release — QUEUED 로 돌려놓고 시도 번호를 되돌려 바로 다시 가져갈 수 있다")
        void release() {
            Job running = queued(1, "KEY").claim(WORKER, NOW, LEASE);

            Job released = running.release(NOW.plusSeconds(5));

            assertThat(released.status()).isEqualTo(JobStatus.QUEUED);
            assertThat(released.attempt()).isZero();
            assertThat(released.scheduledAt()).isEqualTo(NOW.plusSeconds(5));
            assertThat(released.workerId()).isNull();
            assertThat(released.activeDedupKey()).isEqualTo("KEY");
            assertThat(released.claim(OTHER_WORKER, NOW.plusSeconds(5), LEASE).attempt()).as("상한 1 이어도 다시 가져간다").isEqualTo(1);
        }

        @Test
        @DisplayName("취소 요청을 받은 작업은 release 하면 CANCELLED 로 끝난다")
        void releaseWithCancelRequestCancels() {
            assertThat(queued(3, null).claim(WORKER, NOW, LEASE).requestCancel(null, NOW).release(NOW).status()).isEqualTo(JobStatus.CANCELLED);
        }
    }

    private static Job queued(int maxAttempts, String dedupKey) {
        return Job.register(1L, "REPORT", "{}", dedupKey, 9L, 0, NOW, maxAttempts);
    }

    private static Job inStatus(JobStatus status) {
        Job running = queued(1, null).claim(WORKER, NOW, LEASE);
        return switch (status) {
            case QUEUED -> queued(1, null);
            case RUNNING -> running;
            case SUCCEEDED -> running.succeed(null, NOW);
            case FAILED -> running.fail("E", null, false, NOW, NO_JITTER);
            case CANCELLED -> running.cancel(NOW);
        };
    }
}
