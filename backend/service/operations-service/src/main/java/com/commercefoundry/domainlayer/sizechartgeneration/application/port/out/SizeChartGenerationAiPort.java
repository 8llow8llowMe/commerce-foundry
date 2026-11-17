package com.commercefoundry.domainlayer.sizechartgeneration.application.port.out;

import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationImageQueryResult;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationCandidate;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartImageGenerationCandidate;
import java.util.List;
import java.util.Objects;

/** AI 사이즈표 추출. 실패는 {@code SizeChartGenerationRemoteFailure} 로만 알린다. */
public interface SizeChartGenerationAiPort {

    /** TEXT 입력(기능 {@code size-chart}). */
    SizeChartGenerationCandidate generate(String sourceJson, String templateJson, long jobId, int attempt);

    /**
     * 승인한 IMAGE 입력(기능 {@code size-chart-image}).
     *
     * @param imagesJson 이미지 순번 · 형식만 담은 검증한 JSON(해시 · 키 없음)
     * @param images     해시까지 검증한 이미지(1장 이상)
     */
    SizeChartImageGenerationCandidate generateFromImages(String imagesJson, String templateJson, List<Attachment> images, long jobId, int attempt);

    /**
     * 요청에 붙일 이미지 한 장. 바이트는 복사하지 않고 받은 결과를 그대로 가리킨다.
     *
     * @param mediaType 검증 Processor 가 고정 메타데이터와 magic bytes 로 확인한 형식
     */
    record Attachment(String mediaType, SizeChartGenerationImageQueryResult image) {

        public Attachment {
            Objects.requireNonNull(mediaType, "mediaType");
            Objects.requireNonNull(image, "image");
        }
    }
}
