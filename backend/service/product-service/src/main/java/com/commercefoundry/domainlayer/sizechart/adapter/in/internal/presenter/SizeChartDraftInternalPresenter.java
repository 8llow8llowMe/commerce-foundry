package com.commercefoundry.domainlayer.sizechart.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.sizechart.adapter.in.internal.dto.response.SizeChartGenerationInputResponse;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationInputInfo;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

@Component
public class SizeChartDraftInternalPresenter {
    public SizeChartGenerationInputResponse present(SizeChartGenerationInputInfo info) {
        var images = IntStream.range(0, info.images().size())
            .mapToObj(i -> new SizeChartGenerationInputResponse.Image(i, info.images().get(i).sha256(), info.images().get(i).contentType())).toList();
        return new SizeChartGenerationInputResponse(Long.toString(info.draftId()), info.inputHash(), info.sourceJson(), info.templateJson(),
            info.mode(), images.size(), images);
    }
}
