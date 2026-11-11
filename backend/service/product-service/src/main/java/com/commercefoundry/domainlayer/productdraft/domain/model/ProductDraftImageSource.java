package com.commercefoundry.domainlayer.productdraft.domain.model;

import java.util.Objects;

/**
 * 초안을 만들 때 고정한 이미지 원본 (#359 D3 · D11). 공개 주소를 관리 키({@code products/media/**})로 바꾸고 그때의 HEAD 값을 남긴다 —
 * 생성 작업은 이 키를 이 ETag 조건으로만 읽는다(내용이 바뀌었으면 읽지 않는다). 키는 product 안에서만 쓰고 어드민 응답 · internal 메타데이터 · 로그에 싣지 않는다.
 *
 * @param key         관리 오브젝트 스토리지 키
 * @param eTag        고정한 시점의 HEAD ETag(따옴표 포함 그대로)
 * @param length      고정한 시점의 바이트 수
 * @param contentType 고정한 시점의 형식 — {@code image/jpeg} · {@code image/png} · {@code image/webp}
 */
public record ProductDraftImageSource(String key, String eTag, long length, String contentType) {

    public ProductDraftImageSource {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(eTag, "eTag");
        Objects.requireNonNull(contentType, "contentType");
    }

    @Override
    public String toString() {
        return "ProductDraftImageSource[redacted]";
    }
}
