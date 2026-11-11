package com.commercefoundry.domainlayer.sizechart.application.info;

import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartImageSourceRejection;

/**
 * 사이즈표 초안 이미지 후보. 스토리지 키를 담지 않는다.
 *
 * @param previewUrl 우리 공개 저장소 주소일 때만 있다. 외부 주소는 null 이다
 * @param rejection  eligible 이면 null
 */
public record SizeChartImageSourceInfo(String sourceId, String previewUrl, boolean eligible, SizeChartImageSourceRejection rejection) {
}
