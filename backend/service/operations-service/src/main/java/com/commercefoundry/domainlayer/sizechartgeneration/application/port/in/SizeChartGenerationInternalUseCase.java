package com.commercefoundry.domainlayer.sizechartgeneration.application.port.in;

import com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.dto.response.SizeChartGenerationRegistrationResponse;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.dto.response.SizeChartGenerationResultResponse;
import com.commercefoundry.domainlayer.sizechartgeneration.application.command.SizeChartGenerationRegisterCommand;

public interface SizeChartGenerationInternalUseCase {
    SizeChartGenerationRegistrationResponse register(SizeChartGenerationRegisterCommand command);

    SizeChartGenerationResultResponse result(long jobId);
}
