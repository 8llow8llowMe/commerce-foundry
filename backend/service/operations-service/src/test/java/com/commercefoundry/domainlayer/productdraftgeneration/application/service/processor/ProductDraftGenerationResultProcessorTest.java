package com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationErrorCode;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationException;
import com.commercefoundry.domainlayer.productdraftgeneration.application.model.ProductDraftGenerationRemoteFailure;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.ProductDraftGenerationReceiptPort;
import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationReceipt;
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
class ProductDraftGenerationResultProcessorTest {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final String TYPE = ProductDraftGenerationJobProcessor.TYPE;
    private static final String PAYLOAD = "{\"draftId\":10}";
    private static final String RESULT = "{\"suggestion\":{\"name\":\"n\"},\"unconfirmedFacts\":[],\"warnings\":[]}";
    @Mock
    private ProductDraftGenerationReceiptPort receipts;
    private Job currentJob;
    private ProductDraftGenerationResultProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ProductDraftGenerationResultProcessor(receipts, JsonMapper.builder().build());
    }

    private void receipt() {
        given(receipts.findByJobId(20L)).willReturn(Optional.of(new ProductDraftGenerationReceipt(10, 2, 20, HASH, 30, NOW)));
    }

    private Job job(JobStatus status, String result, String error) {
        return job(20, TYPE, PAYLOAD, 30L, status.name(), result, error, status.isFinished() ? NOW : null);
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
    void successReturnsCommittedJsonWithReceiptGenerationEvenAfterDraftExpiry() {
        use(job(JobStatus.SUCCEEDED, RESULT, "ignored"));
        var result = processor.result(20, currentJob);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.draftId()).isEqualTo(10);
        assertThat(result.generationNo()).isEqualTo(2);
        assertThat(result.inputHash()).isEqualTo(HASH);
        assertThat(result.resultJson()).isEqualTo(RESULT);
        assertThat(result.failureCode()).isNull();
        assertThat(result.toString()).doesNotContain("suggestion");
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUN_TIMEOUT", "LEASE_EXPIRED", "HANDLER_ERROR", "RESULT_TOO_LARGE", "GENERATION_FAILED",
        "PRODUCT_DRAFT_INVALID_DATA", "PRODUCT_DRAFT_INPUT_MISMATCH", "PRODUCT_DRAFT_IMAGES_TOO_LARGE"})
    void passesOnlyAllowlistedFailureCode(String code) {
        use(job(JobStatus.FAILED, "private stale result", code));
        var result = processor.result(20, currentJob);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureCode()).isEqualTo(code);
        assertThat(result.resultJson()).isNull();
    }

    @ParameterizedTest
    @EnumSource(ProductDraftGenerationRemoteFailure.Code.class)
    void passesEveryHandlerRemoteFailureCode(ProductDraftGenerationRemoteFailure.Code code) {
        use(job(JobStatus.FAILED, null, code.name()));
        assertThat(processor.result(20, currentJob).failureCode()).isEqualTo(code.name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider body with PII", "SIZE_CHART_INPUT_REJECTED", "product_draft_ai_failed"})
    void unknownFailureBecomesFixedCode(String code) {
        use(job(JobStatus.FAILED, "private stale result", code));
        assertThat(processor.result(20, currentJob).failureCode()).isEqualTo("GENERATION_FAILED");
    }

    @Test
    void nullFailureBecomesFixedCode() {
        use(job(JobStatus.FAILED, null, null));
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
        assertError(ProductDraftGenerationErrorCode.INVALID_RESULT);
    }

    @Test
    void rejectsOversizedUtf8Result() {
        use(job(JobStatus.SUCCEEDED, "{\"value\":\"" + "가".repeat(12000) + "\"}", null));
        assertError(ProductDraftGenerationErrorCode.INVALID_RESULT);
    }

    @Test
    void resultLimitIncludesExactBoundary() {
        String json = "{\"v\":\"" + "x".repeat(ProductDraftGenerationResultProcessor.MAX_RESULT_BYTES - 8) + "\"}";
        use(job(JobStatus.SUCCEEDED, json, null));
        assertThat(processor.result(20, currentJob).resultJson()).hasSize(ProductDraftGenerationResultProcessor.MAX_RESULT_BYTES);
    }

    @Test
    void missingReceiptIsNotFound() {
        assertError(ProductDraftGenerationErrorCode.NOT_FOUND);
    }

    @Test
    void missingJobIsExplicitlyGone() {
        receipt();
        assertError(ProductDraftGenerationErrorCode.RESULT_EXPIRED);
    }

    @Test
    void missingSuccessResultIsExplicitlyGone() {
        use(job(JobStatus.SUCCEEDED, null, null));
        assertError(ProductDraftGenerationErrorCode.RESULT_EXPIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"draftId\":11}", "{\"draftId\":10.0}", "{\"draftId\":\"10\"}", "{\"draftId\":10,\"generationNo\":2}",
        "{\"draftId\":10,\"inputHash\":\"HASH\"}", "{} {}", "[10]"})
    void rejectsPayloadBindingMismatchOrUnexpectedFields(String payload) {
        use(job(20, TYPE, payload.replace("HASH", HASH), 30L, JobStatus.SUCCEEDED.name(), "{}", null, NOW));
        assertError(ProductDraftGenerationErrorCode.INVALID_RESULT);
    }

    @Test
    void rejectsOtherJobType() {
        use(job(20, "SIZE_CHART_GENERATION", PAYLOAD, 30L, JobStatus.SUCCEEDED.name(), "{}", null, NOW));
        assertError(ProductDraftGenerationErrorCode.INVALID_RESULT);
    }

    @Test
    void rejectsActorMismatch() {
        use(job(20, TYPE, PAYLOAD, 31L, JobStatus.SUCCEEDED.name(), "{}", null, NOW));
        assertError(ProductDraftGenerationErrorCode.INVALID_RESULT);
    }

    @Test
    void rejectsDifferentJobId() {
        use(job(21, TYPE, PAYLOAD, 30L, JobStatus.SUCCEEDED.name(), "{}", null, NOW));
        assertError(ProductDraftGenerationErrorCode.INVALID_RESULT);
    }

    @Test
    void rejectsMissingActor() {
        use(job(20, TYPE, PAYLOAD, null, JobStatus.SUCCEEDED.name(), "{}", null, NOW));
        assertError(ProductDraftGenerationErrorCode.INVALID_RESULT);
    }

    private Job job(long id, String type, String payload, Long actor, String status, String result, String error, Instant finishedAt) {
        boolean running = JobStatus.RUNNING.name().equals(status);
        return new Job(id, type, JobStatus.valueOf(status), 0, payload, null, actor, NOW,
            running ? 1 : 0, 2, running ? "worker" : null, running ? NOW.plusSeconds(60) : null, running ? NOW : null,
            finishedAt, null, null, false, null, result, error, null, 0L);
    }

    private void assertError(ProductDraftGenerationErrorCode code) {
        assertThatThrownBy(() -> processor.result(20, currentJob)).isInstanceOfSatisfying(ProductDraftGenerationException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
