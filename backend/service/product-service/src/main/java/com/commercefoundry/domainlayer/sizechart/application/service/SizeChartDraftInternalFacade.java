package com.commercefoundry.domainlayer.sizechart.application.service;

import com.commercefoundry.domainlayer.sizechart.adapter.in.internal.dto.response.SizeChartGenerationInputResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.internal.presenter.SizeChartDraftInternalPresenter;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.port.in.SizeChartDraftInternalUseCase;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftInputProcessor;
import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartImageSourceProcessor;
import com.commercefoundry.global.config.SizeChartDraftProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SizeChartDraftInternalFacade implements SizeChartDraftInternalUseCase {
    private final SizeChartDraftInputProcessor processor;
    private final SizeChartDraftInternalPresenter presenter;
    private final SizeChartImageSourceProcessor imageSources;
    private final SizeChartDraftProperties properties;

    @Override
    public SizeChartGenerationInputResponse generationInput(long draftId) {
        return presenter.present(processor.generationInput(draftId));
    }

    /**
     * 메타데이터는 짧은 읽기 트랜잭션으로, 바이트는 트랜잭션 밖에서 읽는다(스토리지 I/O 동안 DB 커넥션을 쥐지 않는다).
     * 기능을 닫으면 이미 만든 IMAGE 초안의 바이트도 내보내지 않는다(중단 스위치).
     */
    @Override
    public byte[] generationImage(long draftId, int index) {
        if (!properties.isImageEnabled()) {
            throw new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.IMAGE_INPUT_DISABLED);
        }
        return imageSources.generationBytes(processor.generationImage(draftId, index));
    }
}
