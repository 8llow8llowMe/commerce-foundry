package com.commercefoundry.domainlayer.sizechartgeneration.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationErrorCode;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationException;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationReceiptPort;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationReceipt;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class SizeChartGenerationResultProcessorTest {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final String PAYLOAD = "{\"draftId\":10,\"inputHash\":\"" + HASH + "\"}";
    @Mock
    private SizeChartGenerationReceiptPort receipts;
    private Job currentJob;
    private SizeChartGenerationResultProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SizeChartGenerationResultProcessor(receipts, JsonMapper.builder().build());
    }

    private void receipt() {
        given(receipts.findByJobId(20L)).willReturn(Optional.of(new SizeChartGenerationReceipt(10, 20, HASH, 30, NOW)));
    }

    private Job job(JobStatus status, String result, String error) {
        return job(20, "SIZE_CHART_GENERATION", PAYLOAD, 30L, status.name(), result, error,
            status.isFinished() ? NOW : null);
    }

    private void use(Job job) {
        receipt();
        currentJob = job;
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"QUEUED", "RUNNING"})
    void pendingHidesStaleResultAndFailure(JobStatus status) {
        use(job(status, "private stale result", "provider body"));
        var result = processor.result(20, currentJob);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.resultJson()).isNull();
        assertThat(result.failureCode()).isNull();
    }

    @Test
    void successReturnsCommittedJsonEvenAfterDraftExpiry() {
        use(job(JobStatus.SUCCEEDED, "{\"rows\":[]}", "ignored"));
        var result = processor.result(20, currentJob);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.inputHash()).isEqualTo(HASH);
        assertThat(result.resultJson()).isEqualTo("{\"rows\":[]}");
        assertThat(result.failureCode()).isNull();
        assertThat(result.toString()).doesNotContain("rows");
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUN_TIMEOUT", "LEASE_EXPIRED", "HANDLER_ERROR", "RESULT_TOO_LARGE", "GENERATION_FAILED"})
    void passesOnlyAllowlistedFailureCode(String code) {
        use(job(JobStatus.FAILED, "private stale result", code));
        var result = processor.result(20, currentJob);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureCode()).isEqualTo(code);
        assertThat(result.resultJson()).isNull();
    }

    @Test
    void unknownFailureBecomesFixedCode() {
        use(job(JobStatus.FAILED, "private stale result", "provider body with PII"));
        assertThat(processor.result(20, currentJob).failureCode()).isEqualTo("GENERATION_FAILED");
    }

    @Test
    void cancellationHidesFailureAndResult() {
        use(job(JobStatus.CANCELLED, "private stale result", Job.HANDLER_ERROR));
        var result = processor.result(20, currentJob);
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.failureCode()).isNull();
        assertThat(result.resultJson()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "null", "{} {}", "true"})
    void rejectsMalformedOrNonObjectResults(String json) {
        use(job(JobStatus.SUCCEEDED, json, null));
        assertError(SizeChartGenerationErrorCode.INVALID_RESULT);
    }

    @Test
    void rejectsOversizedUtf8Result() {
        use(job(JobStatus.SUCCEEDED, "{\"value\":\"" + "가".repeat(12000) + "\"}", null));
        assertError(SizeChartGenerationErrorCode.INVALID_RESULT);
    }

    @Test
    void resultLimitIncludesExactBoundary() {
        String json = "{\"v\":\"" + "x".repeat(SizeChartGenerationResultProcessor.MAX_RESULT_BYTES - 8) + "\"}";
        use(job(JobStatus.SUCCEEDED, json, null));
        assertThat(processor.result(20, currentJob).resultJson()).hasSize(SizeChartGenerationResultProcessor.MAX_RESULT_BYTES);
    }

    @Test
    void missingReceiptIsNotFound() {
        assertError(SizeChartGenerationErrorCode.NOT_FOUND);
    }

    @Test
    void missingJobIsExplicitlyGone() {
        receipt();
        assertError(SizeChartGenerationErrorCode.RESULT_EXPIRED);
    }

    @Test
    void missingSuccessResultIsExplicitlyGone() {
        use(job(JobStatus.SUCCEEDED, null, null));
        assertError(SizeChartGenerationErrorCode.RESULT_EXPIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"draftId\":11,\"inputHash\":\"HASH\"}",
        "{\"draftId\":10,\"inputHash\":\"wrong\"}", "{\"draftId\":10.0,\"inputHash\":\"HASH\"}",
        "{\"draftId\":10,\"inputHash\":\"HASH\",\"rawText\":\"private\"}", "{} {}"})
    void rejectsPayloadBindingMismatchOrUnexpectedFields(String payload) {
        use(job(20, "SIZE_CHART_GENERATION", payload.replace("HASH", HASH), 30L,
            JobStatus.SUCCEEDED.name(), "{}", null, NOW));
        assertError(SizeChartGenerationErrorCode.INVALID_RESULT);
    }

    @Test
    void rejectsOtherJobType() {
        use(job(20, "OTHER", PAYLOAD, 30L, JobStatus.SUCCEEDED.name(), "{}", null, NOW));
        assertError(SizeChartGenerationErrorCode.INVALID_RESULT);
    }

    @Test
    void rejectsActorMismatch() {
        use(job(20, "SIZE_CHART_GENERATION", PAYLOAD, 31L, JobStatus.SUCCEEDED.name(), "{}", null, NOW));
        assertError(SizeChartGenerationErrorCode.INVALID_RESULT);
    }

    @Test
    void rejectsDifferentJobId() {
        use(job(21, "SIZE_CHART_GENERATION", PAYLOAD, 30L, JobStatus.SUCCEEDED.name(), "{}", null, NOW));
        assertError(SizeChartGenerationErrorCode.INVALID_RESULT);
    }

    @Test
    void rejectsMissingActor() {
        use(job(20, "SIZE_CHART_GENERATION", PAYLOAD, null, JobStatus.SUCCEEDED.name(), "{}", null, NOW));
        assertError(SizeChartGenerationErrorCode.INVALID_RESULT);
    }

    private Job job(long id, String type, String payload, Long actor, String status, String result, String error, Instant finishedAt) {
        boolean running = JobStatus.RUNNING.name().equals(status);
        return new Job(id, type, JobStatus.valueOf(status), 0, payload, null, actor, NOW,
            running ? 1 : 0, 3, running ? "worker" : null, running ? NOW.plusSeconds(60) : null, running ? NOW : null,
            finishedAt, null, null, false, null, result, error, null, 0L);
    }

    private void assertError(SizeChartGenerationErrorCode code) {
        assertThatThrownBy(() -> processor.result(20, currentJob)).isInstanceOfSatisfying(SizeChartGenerationException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
