package com.commercefoundry.domainlayer.display.adapter.in.web.presenter;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.display.adapter.in.web.dto.item.DisplayElementItem;
import com.commercefoundry.domainlayer.display.adapter.in.web.dto.item.DisplayLinkItem;
import com.commercefoundry.domainlayer.display.adapter.in.web.dto.item.DisplayMediaItem;
import com.commercefoundry.domainlayer.display.adapter.in.web.dto.item.DisplayProductItem;
import com.commercefoundry.domainlayer.display.adapter.in.web.dto.item.DisplayProductThumbnailItem;
import com.commercefoundry.domainlayer.display.adapter.in.web.dto.item.DisplaySectionItem;
import com.commercefoundry.domainlayer.display.adapter.in.web.dto.response.DisplayMainResponse;
import com.commercefoundry.domainlayer.display.application.info.DisplayMainInfo;
import com.commercefoundry.domainlayer.display.domain.model.DisplayMedia;
import com.commercefoundry.shared.commerce.Money;
import org.springframework.stereotype.Component;

/**
 * 고객 메인 전시 응답. 식별자 {@code long → String}, 금액 {@code Money → MoneyItem}, 섹션 유형 · 판매 상태 → metadata 변환만 한다.
 * 상품 카드는 상품 목록 Presenter({@code ProductWebPresenter#toListItem})와 같은 규칙으로 옮긴다 — 다른 컨텍스트 Presenter 는 쓰지 않는다.
 */
@Component
public class DisplayWebPresenter {

    public DisplayMainResponse toMainResponse(DisplayMainInfo info) {
        return DisplayMainResponse.builder()
            .sections(info.sections().stream().map(this::toSectionItem).toList())
            .build();
    }

    private DisplaySectionItem toSectionItem(DisplayMainInfo.Section section) {
        return DisplaySectionItem.builder()
            .sectionId(String.valueOf(section.sectionId()))
            .sectionType(section.sectionType().toMetadata())
            .title(section.title())
            .subtitle(section.subtitle())
            .body(section.body())
            .link(section.linkUrl() == null ? null : DisplayLinkItem.builder().url(section.linkUrl()).label(section.linkLabel()).build())
            .items(section.items().stream().map(this::toElementItem).toList())
            .build();
    }

    private DisplayElementItem toElementItem(DisplayMainInfo.Element element) {
        return DisplayElementItem.builder()
            .itemId(String.valueOf(element.itemId()))
            .itemType(element.itemType().name())
            .media(toMediaItem(element.media()))
            .product(toProductItem(element.product()))
            .caption(element.caption())
            .build();
    }

    private DisplayMediaItem toMediaItem(DisplayMedia media) {
        if (media == null) {
            return null;
        }
        return DisplayMediaItem.builder()
            .mediaType(media.mediaType().name())
            .pcUrl(media.pcUrl())
            .mobileUrl(media.mobileUrl())
            .pcPosterUrl(media.pcPosterUrl())
            .mobilePosterUrl(media.mobilePosterUrl())
            .altText(media.altText())
            .build();
    }

    private DisplayProductItem toProductItem(DisplayMainInfo.Product product) {
        if (product == null) {
            return null;
        }
        return DisplayProductItem.builder()
            .productId(String.valueOf(product.productId()))
            .name(product.name())
            .thumbnail(toThumbnailItem(product.thumbnail()))
            .hoverImage(toThumbnailItem(product.hoverImage()))
            .salePrice(toMoneyItem(product.salePrice()))
            .listPrice(toMoneyItem(product.listPrice()))
            .discountRate(product.discountRate())
            .hasOptionSurcharge(product.hasOptionSurcharge())
            .saleStatus(product.saleStatus().toMetadata())
            .customOrder(product.customOrder())
            .preorder(product.preorder())
            .build();
    }

    private DisplayProductThumbnailItem toThumbnailItem(DisplayMainInfo.Thumbnail thumbnail) {
        if (thumbnail == null) {
            return null;
        }
        return DisplayProductThumbnailItem.builder()
            .imageId(String.valueOf(thumbnail.imageId()))
            .url(thumbnail.url())
            .width(thumbnail.width())
            .height(thumbnail.height())
            .altText(thumbnail.altText())
            .build();
    }

    /** null 이면 null 이다 (정가가 없는 상품). 상품 컨텍스트의 {@code MoneyItems} 와 같은 변환이다. */
    private static MoneyItem toMoneyItem(Money money) {
        return money == null ? null : MoneyItem.of(money.amount(), money.currency().name());
    }
}
