package com.commercefoundry.storage.client;

/**
 * 스토리지에 있는 객체의 메타데이터 (HEAD 응답).
 *
 * @param contentLength 바이트 수
 * @param contentType   저장된 Content-Type. 스토리지가 주지 않으면 null. 업로더가 정한 값이라 형식 판단에 쓰지 않는다
 * @param eTag          스토리지가 준 그대로(따옴표 포함). 언제나 있다 — {@code readRange} · {@code copy} 의 {@code expectedETag} 로 그대로 넘긴다
 */
public record StoredObject(long contentLength, String contentType, String eTag) {

}
