package com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor;

import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationErrorCode;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationException;
import com.commercefoundry.domainlayer.productdraftgeneration.application.info.ProductDraftGenerationResultInfo;
import com.commercefoundry.domainlayer.productdraftgeneration.application.model.ProductDraftGenerationRemoteFailure;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.ProductDraftGenerationReceiptPort;
import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationReceipt;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 영수증과 작업을 대조해 product 에 줄 결과를 만든다 (선례: {@code SizeChartGenerationResultProcessor}).
 * 실패 코드는 허용 목록에 있는 고정 코드만 넘기고, 그 밖은 {@link #GENERATION_FAILED} 로 바꾼다.
 * 사이즈표와 달리 핸들러의 고정 실패 코드(api-design-guide.md §7-3 표)도 넘긴다 — product(PR-3)가 "새 초안을 만들라" 와 "다시 시도" 를 가려 안내해야 한다.
 */
@Component
@RequiredArgsConstructor
public class ProductDraftGenerationResultProcessor {
    public static final int MAX_RESULT_BYTES = ProductDraftGenerationValidationProcessor.MAX_RESULT_BYTES;
    public static final String GENERATION_FAILED = "GENERATION_FAILED";
    static final Set<String> FAILURE_CODES = Stream.concat(
        Stream.of(GENERATION_FAILED, Job.LEASE_EXPIRED, Job.RUN_TIMEOUT, Job.HANDLER_ERROR, Job.RESULT_TOO_LARGE,
            ProductDraftGenerationValidationProcessor.INVALID_DATA, ProductDraftGenerationValidationProcessor.INPUT_MISMATCH,
            ProductDraftGenerationValidationProcessor.IMAGES_TOO_LARGE),
        Arrays.stream(ProductDraftGenerationRemoteFailure.Code.values()).map(Enum::name)
    ).collect(Collectors.toUnmodifiableSet());
    private final ProductDraftGenerationReceiptPort receiptPort;
    private final ObjectMapper objectMapper;

    public ProductDraftGenerationResultInfo result(long jobId, Job job) {
        var receipt = receiptPort.findByJobId(jobId)
            .orElseThrow(() -> new ProductDraftGenerationException(ProductDraftGenerationErrorCode.NOT_FOUND));
        if (job == null) {
            throw new ProductDraftGenerationException(ProductDraftGenerationErrorCode.RESULT_EXPIRED);
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
                throw new ProductDraftGenerationException(ProductDraftGenerationErrorCode.RESULT_EXPIRED);
            }
            if (job.resultJson().getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES || !parse(job.resultJson()).isObject()) {
                throw invalidResult();
            }
            resultJson = job.resultJson();
        }
        return new ProductDraftGenerationResultInfo(receipt.draftId(), receipt.generationNo(), jobId, receipt.inputHash(), status, failureCode, resultJson);
    }

    /** 작업이 이 영수증의 것인지 본다 — 종류 · 요청자 · payload {@code {"draftId": 영수증 초안}} 가 모두 맞아야 한다. 회차는 payload 에 없다. */
    public void validateBinding(ProductDraftGenerationReceipt receipt, String type, String payloadJson, Long actor) {
        if (!ProductDraftGenerationJobProcessor.TYPE.equals(type) || actor == null || actor != receipt.requestedByMemberId()
            || payloadJson == null || payloadJson.getBytes(StandardCharsets.UTF_8).length > Job.MAX_PAYLOAD_BYTES) {
            throw invalidResult();
        }
        JsonNode payload = parse(payloadJson);
        JsonNode draftId = payload.get("draftId");
        if (!payload.isObject() || payload.size() != 1 || draftId == null || !draftId.isIntegralNumber() || !draftId.canConvertToLong()
            || draftId.longValue() != receipt.draftId()) {
            throw invalidResult();
        }
    }

    private JsonNode parse(String json) {
        try {
            JsonNode result = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (result == null) {
                throw invalidResult();
            }
            return result;
        } catch (JacksonException exception) {
            // 믿을 수 없는 payload · 결과와 파서 진단을 응답에 싣지 않는다.
            throw invalidResult();
        }
    }

    private ProductDraftGenerationException invalidResult() {
        return new ProductDraftGenerationException(ProductDraftGenerationErrorCode.INVALID_RESULT);
    }
}
