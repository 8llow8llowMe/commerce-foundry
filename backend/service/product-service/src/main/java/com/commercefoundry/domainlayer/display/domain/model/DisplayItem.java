package com.commercefoundry.domainlayer.display.domain.model;

import java.util.Objects;

/**
 * 전시 섹션 안의 아이템 하나 — 미디어 · 상품 또는 룩 참조이다. 다른 유형의 칸은 비어 있다(DB check 제약도 같은 규칙이다).
 * 상품은 아이디로만 가리킨다 — 상품 컨텍스트 domain 을 필드로 갖지 않는다 ({@code architecture-guide.md} §3).
 *
 * @param displayOrder 섹션 안의 순서 (0 부터, 오름차순)
 * @param media 미디어. {@link DisplayItemType#MEDIA} 일 때만 있다
 * @param productId 상품 아이디. {@link DisplayItemType#PRODUCT} 일 때만 있다
 * @param caption 아이템 설명 문구. 없으면 null
 */
public record DisplayItem(long id, long sectionId, int displayOrder, DisplayItemType itemType, DisplayMedia media, Long productId, Long lookId, String caption) {

    public DisplayItem {
        Objects.requireNonNull(itemType, "itemType");
        if (displayOrder < 0) {
            throw new IllegalArgumentException("displayOrder 는 0 이상이어야 합니다.");
        }
        boolean validShape = switch (itemType) {
            case MEDIA -> media != null && productId == null && lookId == null;
            case PRODUCT -> media == null && productId != null && lookId == null;
            case LOOK -> media == null && productId == null && lookId != null && lookId > 0;
        };
        if (!validShape) {
            throw new IllegalArgumentException("아이템 유형과 채운 칸이 맞지 않습니다. itemType=" + itemType);
        }
    }

    /** 미디어 · 상품 아이템 생성용. 룩 참조는 전체 생성자로 만든다. */
    public DisplayItem(long id, long sectionId, int displayOrder, DisplayItemType itemType, DisplayMedia media, Long productId, String caption) {
        this(id, sectionId, displayOrder, itemType, media, productId, null, caption);
    }

    public boolean isLook() {
        return itemType == DisplayItemType.LOOK;
    }

    public boolean isProduct() {
        return itemType == DisplayItemType.PRODUCT;
    }
}
