package com.commercefoundry.domainlayer.sizechartgeneration.application.service.processor;

import com.commercefoundry.domainlayer.job.domain.model.JobExecution;
import com.commercefoundry.domainlayer.job.domain.model.JobExecutionException;
import com.commercefoundry.domainlayer.job.domain.model.JobHandler;
import com.commercefoundry.domainlayer.job.domain.model.JobHandlerOptions;
import com.commercefoundry.domainlayer.job.domain.model.JobOutcome;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationAiPort;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationAiPort.Attachment;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationInputPort;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationInputQueryResult;
import com.commercefoundry.security.principal.AdminPermission;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SizeChartGenerationJobProcessor implements JobHandler {
    private final SizeChartGenerationInputPort inputPort;
    private final SizeChartGenerationAiPort aiPort;
    private final SizeChartGenerationValidationProcessor validation;

    @Override
    public String type() {
        return "SIZE_CHART_GENERATION";
    }

    @Override
    public AdminPermission permission() {
        return AdminPermission.CATALOG_READ;
    }

    @Override
    public String displayName() {
        return "Generate size chart draft";
    }

    @Override
    public String failureNotice(String errorCode) {
        if (SizeChartGenerationRemoteFailure.Code.SIZE_CHART_IMAGE_CHANGED.name().equals(errorCode)) {
            return "The approved size chart image changed. Approve the image again, then create a new draft.";
        }
        return "Could not generate the size chart draft. Check the input and template, then try again.";
    }

    @Override
    public JobHandlerOptions options() {
        return new JobHandlerOptions(null, 2, null, 1);
    }

    @Override
    public JobOutcome execute(JobExecution execution) {
        try {
            // Remote Product/AI I/O runs outside a database transaction; checkpoints retain cancellation/lease semantics.
            var payload = validation.payload(execution.payloadJson());
            execution.checkpoint(5, "Checking size chart input");
            var input = inputPort.getGenerationInput(payload.draftId());
            execution.checkpoint(25, "Size chart input loaded");
            if (input == null || input.draftId() != payload.draftId() || !payload.inputHash().equals(input.inputHash())) {
                throw new JobExecutionException("SIZE_CHART_INPUT_MISMATCH", false);
            }
            String json = validation.isImage(input) ? fromImages(execution, payload.draftId(), input) : fromText(execution, input);
            execution.checkpoint(100, "Size chart draft generated");
            return new JobOutcome(json);
        } catch (SizeChartGenerationRemoteFailure failure) {
            // Translate at the allowed cross-context boundary without carrying a cause into worker logs.
            throw new JobExecutionException(failure.getErrorCode(), failure.isRetryable());
        }
    }

    private String fromText(JobExecution execution, SizeChartGenerationInputQueryResult input) {
        var template = validation.input(input);
        execution.checkpoint(35, "Generating size chart draft");
        var candidate = aiPort.generate(input.sourceJson(), input.templateJson(), execution.jobId(), execution.attempt());
        execution.checkpoint(85, "Validating size chart draft");
        return validation.outcome(candidate, template);
    }

    /**
     * 승인한 IMAGE 입력. 장을 받을 때마다 다음 장을 읽기 전에 해시를 검증하고 checkpoint 를 둔다(진행률 26 ~ 34) — 바뀐 이미지 · 취소 · lease 상실이면
     * 남은 장을 읽지 않고 AI 를 부르지 않는다.
     */
    private String fromImages(JobExecution execution, long draftId, SizeChartGenerationInputQueryResult input) {
        var prepared = validation.imageInput(input);
        int count = prepared.images().size();
        List<Attachment> accepted = new ArrayList<>(count);
        execution.checkpoint(26, "Reading size chart images");
        inputPort.readGenerationImages(draftId, prepared.indexes(), SizeChartGenerationValidationProcessor.MAX_IMAGE_BYTES, received -> {
            accepted.add(validation.image(prepared, accepted, received));
            execution.checkpoint(26 + 8 * accepted.size() / count, "Reading size chart images (" + accepted.size() + "/" + count + ")");
        });
        List<Attachment> images = validation.images(prepared, accepted);
        execution.checkpoint(35, "Generating size chart draft");
        var candidate = aiPort.generateFromImages(prepared.imagesJson(), input.templateJson(), images, execution.jobId(), execution.attempt());
        execution.checkpoint(85, "Validating size chart draft");
        return validation.imageOutcome(candidate, prepared.template());
    }
}
