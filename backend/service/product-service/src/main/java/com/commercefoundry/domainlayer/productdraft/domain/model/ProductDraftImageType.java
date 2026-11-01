package com.commercefoundry.domainlayer.productdraft.domain.model;

/**
 * 초안 근거 이미지 유형. 상품 이미지 유형({@code product.domain.model.ImageType})과 이름을 맞추되 영상은 없다 — AI 는 사진만 본다.
 * 상품 컨텍스트 모델을 DTO · 저장 모양에 쓰지 않으려고 따로 둔다({@code architecture-guide.md} §3 H8). 적용 때 상품 이미지 유형으로 옮긴다.
 * 이름이 {@code input_json} 에 저장되므로 값은 더하기만 한다 — 지우거나 바꾸면 저장된 초안을 읽지 못한다.
 */
public enum ProductDraftImageType {

    /** 목록 대표 이미지. 초안당 한 장까지. */
    THUMBNAIL,

    /** 상세 갤러리 이미지. */
    GALLERY
}
