package com.commercefoundry.domainlayer.sizechart.application.service;

import com.commercefoundry.domainlayer.sizechart.adapter.in.internal.dto.response.SizeChartGenerationInputResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.internal.presenter.SizeChartDraftInternalPresenter;
import com.commercefoundry.domainlayer.sizechart.application.port.in.SizeChartDraftInternalUseCase;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftInputProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SizeChartDraftInternalFacade implements SizeChartDraftInternalUseCase {
    private final SizeChartDraftInputProcessor processor;
    private final SizeChartDraftInternalPresenter presenter;

    @Override
    public SizeChartGenerationInputResponse generationInput(long draftId) {
        return presenter.present(processor.generationInput(draftId));
    }
}
