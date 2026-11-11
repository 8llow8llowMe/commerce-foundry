package com.commercefoundry.domainlayer.productdraft.domain.model;

import java.util.Objects;

/**
 * 초안 근거 이미지 한 장. 주소는 스토어 미디어 저장소의 공개 주소 아래만 받는다(판정은 Processor). 이미지로 사실을 확정하지 않는다 —
 * AI 가 본 것은 제안으로만 남는다.
 *
 * @param imageType 대표({@code THUMBNAIL}) · 갤러리({@code GALLERY}) — 영상은 받지 않는다
 * @param width 원본 가로 px. 모르면 null
 * @param height 원본 세로 px. 모르면 null
 * @param source 만들 때 고정한 관리 키 · ETag · 크기 · 형식(#359 PR-2a). 요청에서 막 바꾼 값 · PR-2a 이전에 저장한 초안은 null 이다
 */
public record ProductDraftImage(String url, ProductDraftImageType imageType, Integer width, Integer height, ProductDraftImageSource source) {

    public ProductDraftImage {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(imageType, "imageType");
    }

    /** 아직 고정하지 않은 이미지 — 요청 본문에서 막 만든 값이다. */
    public ProductDraftImage(String url, ProductDraftImageType imageType, Integer width, Integer height) {
        this(url, imageType, width, height, null);
    }

    /** 고정한 원본을 붙인 사본. */
    public ProductDraftImage pinned(ProductDraftImageSource pinnedSource) {
        return new ProductDraftImage(url, imageType, width, height, Objects.requireNonNull(pinnedSource, "pinnedSource"));
    }
}
