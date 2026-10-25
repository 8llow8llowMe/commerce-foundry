package com.commercefoundry.domainlayer.display.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 미디어 종류. 고객 응답에는 렌더 분기({@code <img>} · {@code <video>})용 제어 코드라 문자열로 내리고({@code coding-conventions.md} §11 예외),
 * 어드민 응답에는 운영자에게 보여 주는 값이라 metadata 로 내린다. 요청은 코드 문자열로 받는다.
 */
@Getter
@RequiredArgsConstructor
public enum DisplayMediaType implements CodeNameDescribable {

    IMAGE("이미지", "사진 한 장입니다."),

    /** 영상. 재생 전 · 자동 재생이 막힌 환경에서 보일 포스터 이미지가 필요하다. */
    VIDEO("영상", "동영상입니다. 재생 전에 보일 포스터 이미지가 필요합니다.");

    private final String displayName;
    private final String description;
}
