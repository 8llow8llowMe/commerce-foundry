package com.commercefoundry.domainlayer.look.domain.model;

import java.util.Objects;

/**
 * 룩 대표 이미지 한 장 — 상품 이미지({@code ProductImage})와 같은 칸이다. PC · 모바일 주소를 나누지 않는다(화면이 같은 주소를 크기만 바꿔 쓴다).
 * 주소 허용 판정(스토어 미디어 저장소의 공개 주소 아래)은 설정을 봐야 해서 Processor 가 한다.
 *
 * @param url 이미지 주소
 * @param width 원본 가로 px. 모르면 null
 * @param height 원본 세로 px. 모르면 null
 * @param altText 대체 텍스트. 없으면 null
 */
public record LookImage(String url, Integer width, Integer height, String altText) {

    public LookImage {
        Objects.requireNonNull(url, "url");
        if (width != null && width <= 0 || height != null && height <= 0) {
            throw new IllegalArgumentException("이미지 크기는 양수여야 합니다. width=" + width + ", height=" + height);
        }
    }
}
