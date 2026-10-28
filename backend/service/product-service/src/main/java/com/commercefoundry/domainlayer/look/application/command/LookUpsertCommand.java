package com.commercefoundry.domainlayer.look.application.command;

import java.util.List;
import java.util.Objects;
import lombok.Builder;

/**
 * 어드민 룩 생성 · 수정(전체 교체). 구성 상품은 받은 순서가 룩 안의 순서다 — 아이템 아이디는 교체마다 새로 발급한다.
 * 문자열 칸은 요청 DTO 가 앞뒤 공백을 빼고 빈 문자열을 null 로 바꿔 넘긴다.
 *
 * @param description 설명. 없으면 null
 * @param productIds 구성 상품 아이디 (받은 순서 = 구성 순서)
 */
@Builder
public record LookUpsertCommand(String title, String description, Image image, boolean visible, List<Long> productIds) {

    public LookUpsertCommand {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(image, "image");
        productIds = productIds == null ? List.of() : List.copyOf(productIds);
    }

    /**
     * @param width 원본 가로 px. 모르면 null
     * @param height 원본 세로 px. 모르면 null
     * @param altText 대체 텍스트. 없으면 null
     */
    @Builder
    public record Image(String url, Integer width, Integer height, String altText) {

        public Image {
            Objects.requireNonNull(url, "url");
        }
    }
}
