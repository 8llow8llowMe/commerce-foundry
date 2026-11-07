package com.commercefoundry.domainlayer.sizechart.application.port.out;

import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartGenerationRegistrationQueryResult;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartGenerationResultQueryResult;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;

/** Call outside a DB transaction. Replay registration with the SAME stored draft after an unknown response. */
public interface SizeChartGenerationPort {
    SizeChartGenerationRegistrationQueryResult register(SizeChartDraft draft);

    SizeChartGenerationResultQueryResult result(SizeChartDraft draft);
}
