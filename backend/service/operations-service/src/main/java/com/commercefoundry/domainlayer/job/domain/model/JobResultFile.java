package com.commercefoundry.domainlayer.job.domain.model;

import java.time.Instant;

public record JobResultFile(
    long id, long jobId, int attempt, String workerId, String objectKey, String fileName, String contentType, long sizeBytes,
    String eTag, boolean sensitive, State state, Instant expiresAt, Instant stagingDeadline, Instant nextDeleteAt,
    int deleteAttempts, Instant lastDeletedAt, Long version
) {
    public enum State { STAGED, AVAILABLE, RETIRED }

    public JobResultFile available(String verifiedETag, Instant now) {
        if (state != State.STAGED || !now.isBefore(stagingDeadline) || !now.isBefore(expiresAt)
            || verifiedETag == null || verifiedETag.isBlank()) {
            throw new IllegalStateException("작업 결과 파일 값이 올바르지 않습니다.");
        }
        return copy(State.AVAILABLE, verifiedETag, expiresAt, deleteAttempts, lastDeletedAt);
    }

    public boolean due(Instant now) {
        return !nextDeleteAt.isAfter(now);
    }

    public JobResultFile retire(Instant now) {
        if (!due(now)) {
            throw new IllegalStateException("작업 결과 파일 값이 올바르지 않습니다.");
        }
        return copy(State.RETIRED, eTag, nextDeleteAt, deleteAttempts, lastDeletedAt);
    }

    public JobResultFile deleted(boolean success, Instant now, Instant next) {
        if (state != State.RETIRED) {
            throw new IllegalStateException("작업 결과 파일 값이 올바르지 않습니다.");
        }
        return copy(state, eTag, next, Math.min(deleteAttempts + 1, 1_000_000), success ? now : lastDeletedAt);
    }

    private JobResultFile copy(State target, String tag, Instant next, int attempts, Instant deletedAt) {
        return new JobResultFile(id, jobId, attempt, workerId, objectKey, fileName, contentType, sizeBytes, tag, sensitive,
            target, expiresAt, stagingDeadline, next, attempts, deletedAt, version);
    }

    @Override
    public String toString() {
        return "JobResultFile[id=" + id + ", jobId=" + jobId + ", state=" + state + "]";
    }
}
