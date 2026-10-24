package com.commercefoundry.domainlayer.display.application.info;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItemType;
import com.commercefoundry.domainlayer.display.domain.model.DisplayMedia;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;

/**
 * 고객 메인 전시 — 지금 보이는 섹션만, 섹션 순서대로.
 */
public record DisplayMainInfo(List<Section> sections) {

    /**
     * @param items 아이템 (섹션 안의 순서). 고객에게 보이지 않는 상품의 아이템은 빠져 있다
     */
    public record Section(
        long sectionId, DisplaySectionType sectionType, String title, String subtitle, String body,
        String linkUrl, String linkLabel, List<Element> items
    ) {
    }

    /**
     * @param media 미디어 아이템이면 있다
     * @param product 상품 아이템이면 있다
     */
    public record Element(long itemId, DisplayItemType itemType, DisplayMedia media, Product product, String caption) {
    }

    /**
     * 상품 카드 — 상품 목록 항목과 같은 값이다. 판매 상태는 응답 metadata 로 바꿀 수 있는 값({@link CodeNameDescribable})으로 든다 —
     * 전시 Presenter 가 상품 컨텍스트 domain enum 을 쓰지 않게 한다 ({@code architecture-guide.md} §3).
     *
     * @param thumbnail 대표 이미지. 없으면 null
     * @param listPrice 정가. 할인이 없으면 null
     * @param discountRate 할인율(%). 할인이 없으면 null
     */
    public record Product(
        long productId, String name, Thumbnail thumbnail,
        Money salePrice, Money listPrice, Integer discountRate, boolean hasOptionSurcharge,
        CodeNameDescribable saleStatus, boolean customOrder, boolean preorder
    ) {
    }

    /**
     * @param width 원본 가로 px. 모르면 null
     * @param height 원본 세로 px. 모르면 null
     */
    public record Thumbnail(long imageId, String url, Integer width, Integer height, String altText) {
    }
}
