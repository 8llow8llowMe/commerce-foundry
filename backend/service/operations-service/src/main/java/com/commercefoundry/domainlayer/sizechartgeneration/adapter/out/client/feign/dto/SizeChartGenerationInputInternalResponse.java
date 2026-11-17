package com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.feign.dto;

import java.util.List;

/**
 * product {@code SizeChartGenerationInputResponse}. {@code mode} 가 없으면(이전 product) TEXT 로 본다.
 *
 * @param images IMAGE 메타데이터. 순서가 이미지 읽기 index 다. 스토리지 키는 오지 않는다
 */
public record SizeChartGenerationInputInternalResponse(String draftId, String inputHash, String sourceJson, String templateJson, String mode,
                                                       Integer imageCount, List<Image> images) {

    public SizeChartGenerationInputInternalResponse(String draftId, String inputHash, String sourceJson, String templateJson) {
        this(draftId, inputHash, sourceJson, templateJson, "TEXT", 0, List.of());
    }

    public record Image(Integer index, String sha256, String contentType) {
    }

    @Override
    public String toString() {
        return "SizeChartGenerationInputInternalResponse[redacted]";
    }
}
