package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftImage;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationInputInfo;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartDraftRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SizeChartDraftInputProcessor {
    private final SizeChartDraftRepositoryPort repository;
    private final Clock clock;
    private final SizeChartDraftValidationProcessor validation;

    @Transactional(readOnly = true)
    public SizeChartGenerationInputInfo generationInput(long draftId) {
        var draft = requested(draftId);
        var input = draft.input();
        String mode = validation.mode(input.sourceJson());
        if ("TEXT".equals(mode)) {
            return new SizeChartGenerationInputInfo(draft.id(), input.inputHash(), mode, input.sourceJson(), input.templateJson(), List.of());
        }
        List<SizeChartDraftImage> images = validation.images(input.sourceJson());
        // 스토리지 키는 product 밖으로 내보내지 않는다. 바이트는 index 로만 읽는다.
        return new SizeChartGenerationInputInfo(draft.id(), input.inputHash(), mode, validation.publicImageSource(images), input.templateJson(),
            images.stream().map(i -> new SizeChartGenerationInputInfo.Image(i.sha256(), i.contentType())).toList());
    }

    /** 생성 작업이 읽을 이미지 한 장의 저장 메타데이터. index 는 0부터다. 바이트 읽기는 호출한 쪽이 트랜잭션 밖에서 한다. */
    @Transactional(readOnly = true)
    public SizeChartDraftImage generationImage(long draftId, int index) {
        var draft = requested(draftId);
        List<SizeChartDraftImage> images = validation.images(draft.input().sourceJson());
        if (index < 0 || index >= images.size()) {
            throw new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.NOT_FOUND);
        }
        return images.get(index);
    }

    private SizeChartDraft requested(long draftId) {
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
        return draft;
    }
}
