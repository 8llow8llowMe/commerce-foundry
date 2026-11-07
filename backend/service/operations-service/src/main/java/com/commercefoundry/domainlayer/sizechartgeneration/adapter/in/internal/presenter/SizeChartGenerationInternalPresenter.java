package com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.dto.response.SizeChartGenerationRegistrationResponse;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.dto.response.SizeChartGenerationResultResponse;
import com.commercefoundry.domainlayer.sizechartgeneration.application.info.SizeChartGenerationRegistrationInfo;
import com.commercefoundry.domainlayer.sizechartgeneration.application.info.SizeChartGenerationResultInfo;
import org.springframework.stereotype.Component;

@Component
public class SizeChartGenerationInternalPresenter {
    public SizeChartGenerationRegistrationResponse registration(SizeChartGenerationRegistrationInfo info) {
        return new SizeChartGenerationRegistrationResponse(Long.toString(info.draftId()), Long.toString(info.jobId()), info.inputHash());
    }

    public SizeChartGenerationResultResponse result(SizeChartGenerationResultInfo info) {
        return new SizeChartGenerationResultResponse(Long.toString(info.draftId()), Long.toString(info.jobId()),
            info.inputHash(), info.status(), info.failureCode(), info.resultJson());
    }
}
