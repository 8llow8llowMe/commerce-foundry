package com.commercefoundry.domainlayer.sizechart.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class SizeChartDraftTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant EXPIRY = NOW.plusSeconds(604800);
    private static final String HASH = "a".repeat(64);
    private static final String PRIVATE_MARKER = "PRIVATE_CONTENT_MARKER";
    private static final SizeChartDraft.Candidate CANDIDATE = SizeChartDraft.Candidate.of("{\"rows\":[]}");

    @Test
    void requestCapturesImmutableBaselineAndCanBecomeReadyAndApplied() {
        SizeChartDraft requested = request();
        SizeChartDraft bound = requested.bindJob(200L, NOW);
        SizeChartDraft ready = bound.ready(CANDIDATE, NOW);
        SizeChartDraft.Candidate reviewed = SizeChartDraft.Candidate.of("{\"rows\":[{\"label\":\"S\"}]}");
        SizeChartDraft applied = ready.apply(reviewed, receipt(HASH), NOW);

        assertThat(requested.status()).isEqualTo(SizeChartDraftStatus.REQUESTED);
        assertThat(requested.jobId()).isNull();
        assertThat(bound.bindJob(200L, NOW)).isSameAs(bound);
        assertThat(ready.status()).isEqualTo(SizeChartDraftStatus.READY);
        assertThat(applied.status()).isEqualTo(SizeChartDraftStatus.APPLIED);
        assertThat(applied.candidate()).isEqualTo(reviewed);
        assertThat(applied.applyReceipt()).isEqualTo(receipt(HASH));
        assertThat(applied.input()).isSameAs(requested.input());
        applied.validateUpdateFrom(ready, NOW);
        assertThat(applied.apply(reviewed, receipt(HASH), EXPIRY.minusNanos(1))).isSameAs(applied);
        assertThatThrownBy(() -> applied.apply(reviewed, receipt(HASH), EXPIRY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> applied.apply(reviewed, receipt("b".repeat(64)), NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requestedCanFailOrCancelBeforeOrAfterBinding() {
        assertThat(request().fail(NOW).status()).isEqualTo(SizeChartDraftStatus.FAILED);
        assertThat(request().cancel(NOW).status()).isEqualTo(SizeChartDraftStatus.CANCELLED);
        assertThat(request().bindJob(200L, NOW).fail(NOW).jobId()).isEqualTo(200L);
        assertThat(request().bindJob(200L, NOW).cancel(NOW).jobId()).isEqualTo(200L);
    }

    @ParameterizedTest
    @EnumSource(SizeChartDraftStatus.class)
    void allDisallowedTransitionsAreRejected(SizeChartDraftStatus status) {
        SizeChartDraft draft = at(status);
        if (status != SizeChartDraftStatus.REQUESTED) {
            assertThatThrownBy(() -> draft.ready(CANDIDATE, NOW)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> draft.fail(NOW)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> draft.cancel(NOW)).isInstanceOf(IllegalStateException.class);
        }
        if (status != SizeChartDraftStatus.READY && status != SizeChartDraftStatus.APPLIED) {
            assertThatThrownBy(() -> draft.apply(CANDIDATE, receipt(HASH), NOW)).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> draft.bindJob(201L, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void exactExpiryAndLaterRejectEveryMutation(long secondsAfterExpiry) {
        Instant now = EXPIRY.plusSeconds(secondsAfterExpiry);
        assertThatThrownBy(() -> request().bindJob(200L, now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request().bindJob(200L, NOW).ready(CANDIDATE, now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request().fail(now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request().cancel(now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> at(SizeChartDraftStatus.READY).apply(CANDIDATE, receipt(HASH), now)).isInstanceOf(IllegalStateException.class);
        assertThat(request().isExpired(now)).isTrue();
    }

    @Test
    void invalidNullPairsAndStateFieldsAreRejected() {
        assertThatThrownBy(() -> input(10L, null, "{}", HASH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> input(null, 0L, "{}", HASH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> input(10L, -1L, "{}", HASH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request().ready(CANDIDATE, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SizeChartDraft(1L, request().input(), 200L, null,
            SizeChartDraftStatus.APPLIED, 0, EXPIRY, receipt(HASH))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SizeChartDraft(1L, request().input(), 200L, CANDIDATE,
            SizeChartDraftStatus.FAILED, 0, EXPIRY, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundedJsonAndSha256AreEnforcedWithoutLeakingContent() {
        assertThatThrownBy(() -> input(null, null, PRIVATE_MARKER.repeat(4000), HASH))
            .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(PRIVATE_MARKER);
        assertThatThrownBy(() -> input(null, null, "{}", "A".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> input(null, null, "{}", "g".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SizeChartDraft.Candidate("{}", HASH)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SizeChartDraft.Candidate.of("{}").hash())
            .isEqualTo("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
        assertThat(input(null, null, "x".repeat(SizeChartDraft.MAX_JSON_LENGTH), HASH).sourceJson()).hasSize(SizeChartDraft.MAX_JSON_LENGTH);
    }

    @Test
    void contentIsExcludedFromAllStringRepresentations() {
        SizeChartDraft.Input input = input(null, null, "{\"text\":\"" + PRIVATE_MARKER + "\"}", HASH);
        SizeChartDraft.Candidate candidate = SizeChartDraft.Candidate.of("{\"value\":\"" + PRIVATE_MARKER + "\"}");
        SizeChartDraft.ApplyReceipt receipt = new SizeChartDraft.ApplyReceipt(HASH, "{\"result\":\"" + PRIVATE_MARKER + "\"}", 100L, NOW);
        SizeChartDraft draft = SizeChartDraft.request(1L, input, EXPIRY).bindJob(200L, NOW).ready(candidate, NOW).apply(candidate, receipt, NOW);
        assertThat(draft.toString()).doesNotContain(PRIVATE_MARKER, input.requestHash(), input.idempotencyKey().toString());
        assertThat(input.toString()).doesNotContain(PRIVATE_MARKER);
        assertThat(candidate.toString()).doesNotContain(PRIVATE_MARKER);
        assertThat(receipt.toString()).doesNotContain(PRIVATE_MARKER);
    }

    @Test
    void reconstructedValuesCannotChangeInputOrSkipBindingAndReadiness() {
        SizeChartDraft original = request();
        SizeChartDraft forged = new SizeChartDraft(original.id(), original.input(), 200L, CANDIDATE,
            SizeChartDraftStatus.READY, 0L, EXPIRY, null);
        assertThatThrownBy(() -> forged.validateUpdateFrom(original, NOW)).isInstanceOf(IllegalStateException.class);
        SizeChartDraft rebound = new SizeChartDraft(original.id(), input(10L, 1L, "{}", HASH), null, null,
            SizeChartDraftStatus.REQUESTED, 0L, EXPIRY, null);
        assertThatThrownBy(() -> rebound.validateUpdateFrom(original, NOW)).isInstanceOf(IllegalStateException.class);
    }

    private static SizeChartDraft at(SizeChartDraftStatus status) {
        SizeChartDraft bound = request().bindJob(200L, NOW);
        return switch (status) {
            case REQUESTED -> bound;
            case READY -> bound.ready(CANDIDATE, NOW);
            case FAILED -> bound.fail(NOW);
            case CANCELLED -> bound.cancel(NOW);
            case APPLIED -> bound.ready(CANDIDATE, NOW).apply(CANDIDATE, receipt(HASH), NOW);
        };
    }

    private static SizeChartDraft request() {
        return SizeChartDraft.request(1L, input(null, null, "{}", HASH), EXPIRY);
    }

    private static SizeChartDraft.Input input(Long chartId, Long revision, String json, String hash) {
        return new SizeChartDraft.Input(10L, 20L, 0L, 100L, UUID.fromString("11111111-1111-4111-8111-111111111111"),
            hash, HASH, json, "{}", chartId, revision);
    }

    private static SizeChartDraft.ApplyReceipt receipt(String hash) {
        return new SizeChartDraft.ApplyReceipt(hash, "{\"chartId\":\"300\",\"revision\":1}", 100L, NOW);
    }
}
