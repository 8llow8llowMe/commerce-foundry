package com.commercefoundry.domainlayer.sizechart.application.model;

import java.util.List;

/**
 * @param sourceJson TEXT 는 저장 입력 그대로, IMAGE 는 키를 뺀 순서 · 해시 · 형식만이다
 * @param images     IMAGE 의 이미지별 메타데이터(순서 = internal 이미지 읽기의 index). TEXT 면 비어 있다
 */
public record SizeChartGenerationInputInfo(long draftId, String inputHash, String mode, String sourceJson, String templateJson, List<Image> images) {

    public SizeChartGenerationInputInfo {
        images = images == null ? List.of() : List.copyOf(images);
    }

    public record Image(String sha256, String contentType) {
    }

    @Override
    public String toString() {
        return "SizeChartGenerationInputInfo[redacted]";
    }
}
