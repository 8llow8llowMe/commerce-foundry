package com.commercefoundry.domainlayer.sizechart.application.port.in;

import com.commercefoundry.domainlayer.sizechart.adapter.in.internal.dto.response.SizeChartGenerationInputResponse;

public interface SizeChartDraftInternalUseCase {
    SizeChartGenerationInputResponse generationInput(long draftId);
}
