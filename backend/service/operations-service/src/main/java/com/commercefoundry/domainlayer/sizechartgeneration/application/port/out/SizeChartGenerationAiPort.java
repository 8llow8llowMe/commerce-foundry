package com.commercefoundry.domainlayer.sizechartgeneration.application.port.out;

import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationCandidate;

public interface SizeChartGenerationAiPort {
    SizeChartGenerationCandidate generate(String sourceJson, String templateJson, long jobId, int attempt);
}
