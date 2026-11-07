package com.commercefoundry.domainlayer.sizechartgeneration.application.service.processor;

import com.commercefoundry.domainlayer.job.domain.model.JobExecution;
import com.commercefoundry.domainlayer.job.domain.model.JobExecutionException;
import com.commercefoundry.domainlayer.job.domain.model.JobHandler;
import com.commercefoundry.domainlayer.job.domain.model.JobHandlerOptions;
import com.commercefoundry.domainlayer.job.domain.model.JobOutcome;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationAiPort;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationInputPort;
import com.commercefoundry.security.principal.AdminPermission;
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
            var template = validation.input(input);
            execution.checkpoint(35, "Generating size chart draft");
            var candidate = aiPort.generate(input.sourceJson(), input.templateJson(), execution.jobId(), execution.attempt());
            execution.checkpoint(85, "Validating size chart draft");
            String json = validation.outcome(candidate, template);
            execution.checkpoint(100, "Size chart draft generated");
            return new JobOutcome(json);
        } catch (SizeChartGenerationRemoteFailure failure) {
            // Translate at the allowed cross-context boundary without carrying a cause into worker logs.
            throw new JobExecutionException(failure.getErrorCode(), failure.isRetryable());
        }
    }
}
