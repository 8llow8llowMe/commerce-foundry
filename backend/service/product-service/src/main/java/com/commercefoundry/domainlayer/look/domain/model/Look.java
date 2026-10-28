package com.commercefoundry.domainlayer.look.domain.model;

import java.util.Objects;

/**
 * 룩 한 개(머리 정보). 구성 상품은 {@link LookContent} 가 함께 든다.
 *
 * @param title 제목 (평문)
 * @param description 설명 (평문, 개행 허용). 없으면 null
 * @param image 대표 이미지
 * @param visible 고객 노출 여부
 * @param displayOrder 룩 목록 안의 순서 (0 부터, 오름차순 — 같으면 아이디 오름차순). 어드민 응답에는 내지 않고 배열 순서로 전한다
 */
public record Look(long id, String title, String description, LookImage image, boolean visible, int displayOrder) {

    public Look {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(image, "image");
        if (displayOrder < 0) {
            throw new IllegalArgumentException("displayOrder 는 0 이상이어야 합니다.");
        }
    }
}
