package com.commercefoundry.domainlayer.sizechart.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.sizechart.adapter.in.internal.dto.response.SizeChartGenerationInputResponse;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationInputInfo;
import org.springframework.stereotype.Component;

@Component
public class SizeChartDraftInternalPresenter {
    public SizeChartGenerationInputResponse present(SizeChartGenerationInputInfo info) {
        return new SizeChartGenerationInputResponse(Long.toString(info.draftId()), info.inputHash(), info.sourceJson(), info.templateJson());
    }
}
