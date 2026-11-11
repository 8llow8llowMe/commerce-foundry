package com.commercefoundry.domainlayer.sizechart.application.model;

/**
 * 초안 입력 스냅숏에 저장한 승인 이미지 한 장. 키는 product 안에서만 쓰고 어드민 응답 · internal 메타데이터 · 로그에 싣지 않는다.
 *
 * @param key         관리되는 오브젝트 스토리지 키
 * @param sha256      승인한 바이트의 SHA-256(소문자 16진수)
 * @param contentType magic bytes 로 판별한 MIME
 * @param length      바이트 수
 */
public record SizeChartDraftImage(String key, String sha256, String contentType, long length) {
    @Override
    public String toString() {
        return "SizeChartDraftImage[redacted]";
    }
}
