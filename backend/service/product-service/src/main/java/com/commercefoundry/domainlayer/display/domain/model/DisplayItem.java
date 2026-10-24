package com.commercefoundry.domainlayer.display.domain.model;

import java.util.Objects;

/**
 * 전시 섹션 안의 아이템 하나 — 미디어 또는 상품이다. 다른 유형의 칸은 비어 있다(DB check 제약도 같은 규칙이다).
 * 상품은 아이디로만 가리킨다 — 상품 컨텍스트 domain 을 필드로 갖지 않는다 ({@code architecture-guide.md} §3).
 *
 * @param displayOrder 섹션 안의 순서 (0 부터, 오름차순)
 * @param media 미디어. {@link DisplayItemType#MEDIA} 일 때만 있다
 * @param productId 상품 아이디. {@link DisplayItemType#PRODUCT} 일 때만 있다
 * @param caption 아이템 설명 문구. 없으면 null
 */
public record DisplayItem(long id, long sectionId, int displayOrder, DisplayItemType itemType, DisplayMedia media, Long productId, String caption) {

    public DisplayItem {
        Objects.requireNonNull(itemType, "itemType");
        if (displayOrder < 0) {
            throw new IllegalArgumentException("displayOrder 는 0 이상이어야 합니다.");
        }
        boolean mediaShape = media != null && productId == null;
        boolean productShape = media == null && productId != null;
        if (itemType == DisplayItemType.MEDIA ? !mediaShape : !productShape) {
            throw new IllegalArgumentException("아이템 유형과 채운 칸이 맞지 않습니다. itemType=" + itemType);
        }
    }

    public boolean isProduct() {
        return itemType == DisplayItemType.PRODUCT;
    }
}
