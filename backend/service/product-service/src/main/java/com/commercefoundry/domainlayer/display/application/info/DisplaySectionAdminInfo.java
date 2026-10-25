package com.commercefoundry.domainlayer.display.application.info;

import com.commercefoundry.domainlayer.display.domain.model.DisplayItemType;
import com.commercefoundry.domainlayer.display.domain.model.DisplayLiveStatus;
import com.commercefoundry.domainlayer.display.domain.model.DisplayMedia;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
import java.time.Instant;
import java.util.List;

/**
 * 어드민 전시 섹션 한 개 — 숨김 · 기간 밖 섹션과 숨긴 상품의 아이템도 담는다.
 *
 * @param liveStatus 지금 노출 상태 ({@code DisplaySection#liveStatusAt} — 고객 메인 필터와 같은 판정)
 * @param items 아이템 전체 (섹션 안의 순서)
 */
public record DisplaySectionAdminInfo(
    long sectionId, DisplaySectionType sectionType, String title, String subtitle, String body,
    String linkUrl, String linkLabel, int displayOrder, boolean visible, Instant startAt, Instant endAt,
    DisplayLiveStatus liveStatus, List<Element> items
) {

    /**
     * @param media 미디어 아이템이면 있다
     * @param product 상품 아이템이면 있다
     */
    public record Element(long itemId, DisplayItemType itemType, DisplayMedia media, ProductReference product, String caption) {
    }

    /**
     * 상품 아이템이 가리키는 상품 — 어드민이 알아볼 정도만 담는다.
     *
     * @param name 상품명. 상품 행이 없으면(데이터 오류) null
     * @param visible 고객 노출 여부. 숨긴 상품은 고객 메인에서 빠진다. 상품 행이 없으면 false
     */
    public record ProductReference(long productId, String name, boolean visible) {
    }
}
