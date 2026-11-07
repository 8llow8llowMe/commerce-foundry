package com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.persistence.entity.SizeChartGenerationReceiptEntity;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface SizeChartGenerationReceiptRepository extends Repository<SizeChartGenerationReceiptEntity, Long> {
    Optional<SizeChartGenerationReceiptEntity> findById(long draftId);

    Optional<SizeChartGenerationReceiptEntity> findByJobId(long jobId);
}
