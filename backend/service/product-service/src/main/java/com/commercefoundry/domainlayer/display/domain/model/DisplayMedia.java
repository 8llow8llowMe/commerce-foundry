package com.commercefoundry.domainlayer.display.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 전시 미디어 한 개. 모바일 주소가 없으면 화면이 PC 주소를 쓴다.
 *
 * <p>영상은 PC 포스터가 반드시 있고, 이미지는 포스터를 갖지 않는다. 요청 검증이 같은 규칙을 먼저 보므로 여기서 걸리면 호출 코드의 오류다.
 *
 * @param mobileUrl 모바일 주소. 없으면 null — PC 주소를 쓴다
 * @param pcPosterUrl 영상 포스터(PC). 영상이면 필수, 이미지면 null
 * @param mobilePosterUrl 영상 포스터(모바일). 없으면 null — PC 포스터를 쓴다
 * @param altText 대체 텍스트. 없으면 null
 */
public record DisplayMedia(DisplayMediaType mediaType, String pcUrl, String mobileUrl, String pcPosterUrl, String mobilePosterUrl, String altText) {

    public DisplayMedia {
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(pcUrl, "pcUrl");
        if (mediaType == DisplayMediaType.VIDEO && pcPosterUrl == null) {
            throw new IllegalArgumentException("영상은 PC 포스터가 필요합니다.");
        }
        if (mediaType == DisplayMediaType.IMAGE && (pcPosterUrl != null || mobilePosterUrl != null)) {
            throw new IllegalArgumentException("이미지는 포스터를 갖지 않습니다.");
        }
    }

    /** 저장소 주소 판정 대상 — 비어 있지 않은 주소 전부 (PC · 모바일 · 포스터). */
    public List<String> urls() {
        List<String> urls = new ArrayList<>(4);
        urls.add(pcUrl);
        if (mobileUrl != null) {
            urls.add(mobileUrl);
        }
        if (pcPosterUrl != null) {
            urls.add(pcPosterUrl);
        }
        if (mobilePosterUrl != null) {
            urls.add(mobilePosterUrl);
        }
        return List.copyOf(urls);
    }
}
