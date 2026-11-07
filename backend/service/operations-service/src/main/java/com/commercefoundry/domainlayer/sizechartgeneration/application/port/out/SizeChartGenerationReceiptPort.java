package com.commercefoundry.domainlayer.sizechartgeneration.application.port.out;

import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationReceipt;
import java.util.Optional;

public interface SizeChartGenerationReceiptPort {
    Optional<SizeChartGenerationReceipt> findByDraftId(long draftId);

    Optional<SizeChartGenerationReceipt> findByJobId(long jobId);

    void insert(SizeChartGenerationReceipt receipt);
}
