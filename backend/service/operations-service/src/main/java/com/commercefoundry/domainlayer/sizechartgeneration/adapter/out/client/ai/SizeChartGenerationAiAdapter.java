package com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.ai;

import com.commercefoundry.ai.client.AiClient;
import com.commercefoundry.ai.client.AiException;
import com.commercefoundry.ai.prompt.PromptTemplates;
import com.commercefoundry.ai.prompt.PromptValues;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure.Code;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationAiPort;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SizeChartGenerationAiAdapter implements SizeChartGenerationAiPort {
    private final AiClient aiClient;
    private final PromptTemplates templates;

    @Override
    public SizeChartGenerationCandidate generate(String sourceJson, String templateJson, long jobId, int attempt) {
        try {
            var request = templates.get("size-chart").render(PromptValues.create()
                .put("sourceJson", sourceJson).put("templateJson", templateJson)).withCallerRef("job:" + jobId + ":" + attempt);
            return aiClient.complete(request, SizeChartGenerationCandidate.class).value();
        } catch (AiException exception) {
            // Fixed codes only. Even provider errorType/feature may contain untrusted text.
            throw new SizeChartGenerationRemoteFailure(Code.SIZE_CHART_AI_FAILED, exception.reason().retryable());
        } catch (IllegalArgumentException exception) {
            throw new SizeChartGenerationRemoteFailure(Code.SIZE_CHART_AI_INVALID, false);
        }
    }
}
