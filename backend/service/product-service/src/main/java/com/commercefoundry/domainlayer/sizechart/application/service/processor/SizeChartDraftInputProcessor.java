package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationInputInfo;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartDraftRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SizeChartDraftInputProcessor {
    private final SizeChartDraftRepositoryPort repository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public SizeChartGenerationInputInfo generationInput(long draftId) {
        if (draftId <= 0) {
            throw new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.NOT_FOUND);
        }
        var draft = repository.findById(draftId)
            .orElseThrow(() -> new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.NOT_FOUND));
        if (draft.isExpired(clock.instant())) {
            throw new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.EXPIRED);
        }
        // The registration response can be lost before product binds jobId. REQUESTED is sufficient.
        if (draft.status() != SizeChartDraftStatus.REQUESTED) {
            throw new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.INVALID_STATUS);
        }
        var input = draft.input();
        return new SizeChartGenerationInputInfo(draft.id(), input.inputHash(), input.sourceJson(), input.templateJson());
    }
}
