package com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client;

import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.feign.SizeChartGenerationInputClient;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.support.SizeChartGenerationInputInternalResponseSupport;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationInputPort;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationInputQueryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SizeChartGenerationInputClientAdapter implements SizeChartGenerationInputPort {
    private final SizeChartGenerationInputClient client;
    private final SizeChartGenerationInputInternalResponseSupport support;

    @Override
    public SizeChartGenerationInputQueryResult getGenerationInput(long draftId) {
        return support.toQueryResult(support.call(() -> client.getGenerationInput(draftId)));
    }
}
