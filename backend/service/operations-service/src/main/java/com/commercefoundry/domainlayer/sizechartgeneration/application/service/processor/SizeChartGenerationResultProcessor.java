package com.commercefoundry.domainlayer.sizechartgeneration.application.service.processor;

import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationErrorCode;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationException;
import com.commercefoundry.domainlayer.sizechartgeneration.application.info.SizeChartGenerationResultInfo;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationReceiptPort;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationReceipt;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class SizeChartGenerationResultProcessor {
    public static final int MAX_RESULT_BYTES = 32 * 1024;
    public static final String GENERATION_FAILED = "GENERATION_FAILED";
    private static final Set<String> FAILURE_CODES = Set.of(
        GENERATION_FAILED, Job.LEASE_EXPIRED, Job.RUN_TIMEOUT, Job.HANDLER_ERROR, Job.RESULT_TOO_LARGE
    );
    private final SizeChartGenerationReceiptPort receiptPort;
    private final ObjectMapper objectMapper;

    public SizeChartGenerationResultInfo result(long jobId, Job job) {
        var receipt = receiptPort.findByJobId(jobId)
            .orElseThrow(() -> new SizeChartGenerationException(SizeChartGenerationErrorCode.NOT_FOUND));
        if (job == null) {
            throw new SizeChartGenerationException(SizeChartGenerationErrorCode.RESULT_EXPIRED);
        }
        if (job.id() != receipt.jobId()) {
            throw invalidResult();
        }
        validateBinding(receipt, job.type(), job.payloadJson(), job.requestedByMemberId());
        JobStatus jobStatus = job.status();
        String status = jobStatus.isActive() ? "PENDING" : jobStatus.name();
        String failureCode = null;
        String resultJson = null;
        if (jobStatus.isFinished() && job.finishedAt() == null) {
            throw invalidResult();
        }
        if (jobStatus == JobStatus.FAILED) {
            failureCode = FAILURE_CODES.contains(job.errorCode() == null ? "" : job.errorCode()) ? job.errorCode() : GENERATION_FAILED;
        }
        if (jobStatus == JobStatus.SUCCEEDED) {
            if (job.resultJson() == null) {
                throw new SizeChartGenerationException(SizeChartGenerationErrorCode.RESULT_EXPIRED);
            }
            if (job.resultJson().getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES || !parse(job.resultJson()).isObject()) {
                throw invalidResult();
            }
            resultJson = job.resultJson();
        }
        return new SizeChartGenerationResultInfo(receipt.draftId(), jobId, receipt.inputHash(), status, failureCode, resultJson);
    }

    public void validateBinding(SizeChartGenerationReceipt receipt, String type, String payloadJson, Long actor) {
        if (!SizeChartGenerationReceiptProcessor.JOB_TYPE.equals(type) || actor == null || actor != receipt.requestedByMemberId()
            || payloadJson == null || payloadJson.getBytes(StandardCharsets.UTF_8).length > Job.MAX_PAYLOAD_BYTES) {
            throw invalidResult();
        }
        JsonNode payload = parse(payloadJson);
        JsonNode draftId = payload.get("draftId");
        JsonNode hash = payload.get("inputHash");
        if (!payload.isObject() || payload.size() != 2 || draftId == null || !draftId.isIntegralNumber() || !draftId.canConvertToLong()
            || draftId.longValue() != receipt.draftId() || hash == null || !hash.isString() || !receipt.inputHash().equals(hash.stringValue())) {
            throw invalidResult();
        }
    }

    private JsonNode parse(String json) {
        try {
            JsonNode result = objectMapper.reader().with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (result == null) {
                throw invalidResult();
            }
            return result;
        } catch (JacksonException exception) {
            // Never include untrusted payload/result or parser diagnostics in the API response.
            throw invalidResult();
        }
    }

    private SizeChartGenerationException invalidResult() {
        return new SizeChartGenerationException(SizeChartGenerationErrorCode.INVALID_RESULT);
    }
}
