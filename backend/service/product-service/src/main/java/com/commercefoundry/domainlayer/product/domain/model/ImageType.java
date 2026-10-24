package com.commercefoundry.domainlayer.product.domain.model;

public enum ImageType {

    /** 목록 대표 이미지. 상품당 1개이고 순서는 항상 0 이다. */
    THUMBNAIL,

    /** 상세 갤러리 이미지. */
    GALLERY,

    /** 상세 갤러리 영상. url 은 영상 파일이고 포스터 이미지가 반드시 있다. 갤러리 이미지와 노출 순서로 섞여 보인다. 대표 이미지로 쓰지 않는다. */
    VIDEO;

    public boolean isVideo() {
        return this == VIDEO;
    }
}
