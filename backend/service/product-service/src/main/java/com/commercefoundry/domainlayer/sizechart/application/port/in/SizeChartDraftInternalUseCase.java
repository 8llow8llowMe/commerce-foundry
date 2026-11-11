package com.commercefoundry.domainlayer.sizechart.application.port.in;

import com.commercefoundry.domainlayer.sizechart.adapter.in.internal.dto.response.SizeChartGenerationInputResponse;

public interface SizeChartDraftInternalUseCase {
    SizeChartGenerationInputResponse generationInput(long draftId);

    /** IMAGE 초안의 승인 이미지 바이트. 읽는 시점의 SHA-256 이 저장 값과 다르면 다시 시도해도 안 되는 오류다. */
    byte[] generationImage(long draftId, int index);
}
