package com.commercefoundry.domainlayer.sizechartgeneration.application.port.out;

import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationInputQueryResult;

public interface SizeChartGenerationInputPort {
    SizeChartGenerationInputQueryResult getGenerationInput(long draftId);
}
