package com.commercefoundry.domainlayer.productdraft.domain.model;

import java.util.Objects;

/**
 * 초안 근거 이미지 한 장. 주소는 스토어 미디어 저장소의 공개 주소 아래만 받는다(판정은 Processor). 이미지로 사실을 확정하지 않는다 —
 * AI 가 본 것은 제안으로만 남는다.
 *
 * @param imageType 대표({@code THUMBNAIL}) · 갤러리({@code GALLERY}) — 영상은 받지 않는다
 * @param width 원본 가로 px. 모르면 null
 * @param height 원본 세로 px. 모르면 null
 */
public record ProductDraftImage(String url, ProductDraftImageType imageType, Integer width, Integer height) {

    public ProductDraftImage {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(imageType, "imageType");
    }
}
