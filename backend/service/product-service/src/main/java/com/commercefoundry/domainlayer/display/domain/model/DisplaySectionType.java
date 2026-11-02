package com.commercefoundry.domainlayer.display.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 메인 전시 섹션의 블록 유형. 브랜드와 무관한 블록 이름이다 — 어느 쇼핑몰이든 같은 블록을 조합한다 ({@code architecture-guide.md} §9).
 * 유형마다 받는 아이템 수가 정해져 있다(상한 · 하한 모두 포함). 상한이 0 이면 그 아이템 유형을 받지 않는다.
 *
 * <p>LOOK 은 룩 아이디만 참조한다. 화보와 구성 상품은 룩 컨텍스트 한 곳에서 관리한다 (#372).
 */
@Getter
@RequiredArgsConstructor
public enum DisplaySectionType implements CodeNameDescribable {

    HERO("히어로", "메인 상단의 큰 이미지 · 영상 배너입니다. 여러 장이면 슬라이드로 넘깁니다.", 1, 10, 0, 0),
    PRODUCT_PICK("상품 픽", "운영자가 고른 상품 묶음입니다.", 0, 0, 1, 20),
    STORY("스토리", "브랜드 이야기 · 기획 글입니다. 이미지 · 영상은 선택입니다.", 0, 10, 0, 0),
    LOOK("룩", "등록된 룩의 화보와 구성 상품을 보여 줍니다.", 0, 0, 0, 0),
    DETAIL("디테일", "소재 · 디테일을 크게 보여 주는 이미지 · 영상 블록입니다.", 1, 10, 0, 0);

    private final String displayName;
    private final String description;
    private final int minMediaCount;
    private final int maxMediaCount;
    private final int minProductCount;
    private final int maxProductCount;

    public int minCountOf(DisplayItemType itemType) {
        return itemType == DisplayItemType.LOOK ? (this == LOOK ? 1 : 0) : itemType == DisplayItemType.MEDIA ? minMediaCount : minProductCount;
    }

    public int maxCountOf(DisplayItemType itemType) {
        return itemType == DisplayItemType.LOOK ? (this == LOOK ? 10 : 0) : itemType == DisplayItemType.MEDIA ? maxMediaCount : maxProductCount;
    }

    public boolean allows(DisplayItemType itemType) {
        return maxCountOf(itemType) > 0;
    }
}
