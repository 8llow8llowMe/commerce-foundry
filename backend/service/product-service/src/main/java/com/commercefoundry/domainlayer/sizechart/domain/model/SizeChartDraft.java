package com.commercefoundry.domainlayer.sizechart.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * 불변 초안. ID는 호출자가 Snowflake로 발급하며, 입력은 상위 계층에서 개인정보를 제거한 JSON만 받는다.
 * 저장 계약은 개인정보 제거 여부나 차트의 의미를 추측하지 않는다. JSON 원문은 문자열 표현과 예외에서 제외한다.
 */
public record SizeChartDraft(
    long id,
    Input input,
    Long jobId,
    Candidate candidate,
    SizeChartDraftStatus status,
    long version,
    Instant expiresAt,
    ApplyReceipt applyReceipt
) {

    public static final int MAX_JSON_LENGTH = 32768;

    public SizeChartDraft {
        positive(id, "id");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (version < 0 || (jobId != null && jobId <= 0)) {
            throw new IllegalArgumentException("Invalid draft version or job ID");
        }
        boolean hasCandidate = status == SizeChartDraftStatus.READY || status == SizeChartDraftStatus.APPLIED;
        if (hasCandidate != (candidate != null) || (hasCandidate && jobId == null)
            || ((status == SizeChartDraftStatus.APPLIED) != (applyReceipt != null))) {
            throw new IllegalArgumentException("Invalid draft lifecycle fields");
        }
    }

    public static SizeChartDraft request(long id, Input input, Instant expiresAt) {
        return new SizeChartDraft(id, input, null, null, SizeChartDraftStatus.REQUESTED, 0, expiresAt, null);
    }

    public boolean isExpired(Instant now) {
        return !Objects.requireNonNull(now, "now").isBefore(expiresAt);
    }

    public SizeChartDraft bindJob(long newJobId, Instant now) {
        requireLive(now);
        if (jobId != null) {
            if (jobId != newJobId) {
                throw new IllegalStateException("Draft job binding conflict");
            }
            return this;
        }
        requireStatus(SizeChartDraftStatus.REQUESTED);
        return new SizeChartDraft(id, input, newJobId, null, status, version, expiresAt, null);
    }

    public SizeChartDraft ready(Candidate result, Instant now) {
        requireLive(now);
        requireStatus(SizeChartDraftStatus.REQUESTED);
        return new SizeChartDraft(id, input, jobId, Objects.requireNonNull(result, "candidate"),
            SizeChartDraftStatus.READY, version, expiresAt, null);
    }

    public SizeChartDraft fail(Instant now) {
        return terminate(SizeChartDraftStatus.FAILED, now);
    }

    public SizeChartDraft cancel(Instant now) {
        return terminate(SizeChartDraftStatus.CANCELLED, now);
    }

    /** 상품 표 교체와 같은 트랜잭션에서 저장한다. 동일 해시 재전송은 만료 전까지만 영수증을 재생한다. */
    public SizeChartDraft apply(Candidate reviewed, ApplyReceipt receipt, Instant now) {
        requireLive(now);
        Objects.requireNonNull(receipt, "receipt");
        if (status == SizeChartDraftStatus.APPLIED) {
            if (!applyReceipt.applyHash().equals(receipt.applyHash())) {
                throw new IllegalStateException("Draft apply replay conflict");
            }
            return this;
        }
        requireLive(now);
        requireStatus(SizeChartDraftStatus.READY);
        if (!receipt.appliedAt().equals(now)) {
            throw new IllegalArgumentException("Apply receipt time mismatch");
        }
        return new SizeChartDraft(id, input, jobId, Objects.requireNonNull(reviewed, "reviewed"),
            SizeChartDraftStatus.APPLIED, version, expiresAt, receipt);
    }

    /** 재구축한 값도 관리 엔티티에 반영하기 전에 기존 상태에서 허용된 전이인지 확인한다. */
    public void validateUpdateFrom(SizeChartDraft previous, Instant now) {
        if (id != previous.id || !input.equals(previous.input) || version != previous.version || !expiresAt.equals(previous.expiresAt)) {
            throw new IllegalStateException("Draft immutable fields or version changed");
        }
        if (equals(previous)) {
            return;
        }
        SizeChartDraft expected;
        if (!Objects.equals(jobId, previous.jobId)) {
            expected = previous.bindJob(Objects.requireNonNull(jobId, "jobId"), now);
        } else {
            expected = switch (status) {
                case REQUESTED -> previous;
                case READY -> previous.ready(candidate, now);
                case FAILED -> previous.fail(now);
                case CANCELLED -> previous.cancel(now);
                case APPLIED -> previous.apply(candidate, applyReceipt, now);
            };
        }
        if (!equals(expected)) {
            throw new IllegalStateException("Invalid draft update");
        }
    }

    private SizeChartDraft terminate(SizeChartDraftStatus target, Instant now) {
        requireLive(now);
        requireStatus(SizeChartDraftStatus.REQUESTED);
        return new SizeChartDraft(id, input, jobId, null, target, version, expiresAt, null);
    }

    private void requireLive(Instant now) {
        if (isExpired(now)) {
            throw new IllegalStateException("Draft expired");
        }
    }

    private void requireStatus(SizeChartDraftStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Invalid draft transition");
        }
    }

    @Override
    public String toString() {
        return "SizeChartDraft[id=" + id + ", status=" + status + ", version=" + version + "]";
    }

    public record Input(
        long productId,
        long templateId,
        long templateRevision,
        long requestedByMemberId,
        UUID idempotencyKey,
        String requestHash,
        String inputHash,
        String sourceJson,
        String templateJson,
        Long baseChartId,
        Long baseChartRevision
    ) {
        public Input {
            positive(productId, "productId");
            positive(templateId, "templateId");
            positive(requestedByMemberId, "requestedByMemberId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            hash(requestHash);
            hash(inputHash);
            json(sourceJson);
            json(templateJson);
            if (templateRevision < 0 || (baseChartId == null) != (baseChartRevision == null)
                || (baseChartId != null && (baseChartId <= 0 || baseChartRevision < 0))) {
                throw new IllegalArgumentException("Invalid captured revision");
            }
        }

        @Override
        public String toString() {
            return "SizeChartDraft.Input[redacted]";
        }
    }

    public record Candidate(String json, String hash) {
        public Candidate {
            SizeChartDraft.json(json);
            SizeChartDraft.hash(hash);
            if (!sha256(json).equals(hash)) {
                throw new IllegalArgumentException("Candidate hash mismatch");
            }
        }

        public static Candidate of(String sanitizedJson) {
            return new Candidate(sanitizedJson, sha256(sanitizedJson));
        }

        @Override
        public String toString() {
            return "SizeChartDraft.Candidate[redacted]";
        }
    }

    /** 안전한 적용 응답 JSON. 요청과 경고 확인을 묶는 정규 해시는 상위 계층에서 계산한다. */
    public record ApplyReceipt(String applyHash, String receiptJson, long appliedByMemberId, Instant appliedAt) {
        public ApplyReceipt {
            hash(applyHash);
            json(receiptJson);
            positive(appliedByMemberId, "appliedByMemberId");
            Objects.requireNonNull(appliedAt, "appliedAt");
        }

        @Override
        public String toString() {
            return "SizeChartDraft.ApplyReceipt[redacted]";
        }
    }

    private static void positive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected lowercase SHA-256 hex");
        }
    }

    private static void json(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException("Invalid sanitized JSON length");
        }
    }

    private static String sha256(String value) {
        Objects.requireNonNull(value, "candidateJson");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
