package com.commercefoundry.domainlayer.sizechart.application.info;

/** 운영자가 승인할 이미지 바이트의 확인 결과. 키 · 바이트를 담지 않는다. */
public record SizeChartImageInspectionInfo(String sourceId, String contentHash, String contentType, long contentLength) {
}
