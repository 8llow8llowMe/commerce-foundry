package com.commercefoundry.domainlayer.display.application.command;

import com.commercefoundry.domainlayer.display.domain.model.DisplayItemType;
import com.commercefoundry.domainlayer.display.domain.model.DisplayMediaType;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.Builder;

/**
 * 어드민 전시 섹션 생성 · 수정(전체 교체). 아이템은 받은 순서가 섹션 안의 순서다 — 아이템 아이디는 교체마다 새로 발급한다.
 * 문자열 칸은 요청 DTO 가 앞뒤 공백을 빼고 빈 문자열을 null 로 바꿔 넘긴다.
 *
 * @param items 아이템 전체 (받은 순서). 없으면 빈 목록
 */
@Builder
public record DisplaySectionUpsertCommand(
    DisplaySectionType sectionType, String title, String subtitle, String body,
    String linkUrl, String linkLabel, boolean visible, Instant startAt, Instant endAt,
    List<Item> items
) {

    public DisplaySectionUpsertCommand {
        Objects.requireNonNull(sectionType, "sectionType");
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * @param media 미디어. {@link DisplayItemType#MEDIA} 일 때만 있다
     * @param productId 상품 아이디. {@link DisplayItemType#PRODUCT} 일 때만 있다
     */
    @Builder
    public record Item(DisplayItemType itemType, Media media, Long productId, String caption) {

        public Item {
            Objects.requireNonNull(itemType, "itemType");
        }
    }

    @Builder
    public record Media(DisplayMediaType mediaType, String pcUrl, String mobileUrl, String pcPosterUrl, String mobilePosterUrl, String altText) {

        public Media {
            Objects.requireNonNull(mediaType, "mediaType");
            Objects.requireNonNull(pcUrl, "pcUrl");
        }
    }
}
