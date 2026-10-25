package com.commercefoundry.domainlayer.display.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.item.DisplayElementAdminItem;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.item.DisplayMediaAdminItem;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.item.DisplayProductAdminItem;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response.DisplaySectionAdminListResponse;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response.DisplaySectionAdminResponse;
import com.commercefoundry.domainlayer.display.application.info.DisplaySectionAdminInfo;
import com.commercefoundry.domainlayer.display.domain.model.DisplayMedia;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 어드민 전시 응답. 식별자 {@code long → String}, 섹션 유형 → metadata 변환만 한다.
 */
@Component
public class DisplayAdminPresenter {

    public DisplaySectionAdminListResponse toListResponse(List<DisplaySectionAdminInfo> infos) {
        return DisplaySectionAdminListResponse.builder()
            .sections(infos.stream().map(this::toResponse).toList())
            .build();
    }

    public DisplaySectionAdminResponse toResponse(DisplaySectionAdminInfo info) {
        return DisplaySectionAdminResponse.builder()
            .sectionId(String.valueOf(info.sectionId()))
            .sectionType(info.sectionType().toMetadata())
            .title(info.title())
            .subtitle(info.subtitle())
            .body(info.body())
            .linkUrl(info.linkUrl())
            .linkLabel(info.linkLabel())
            .visible(info.visible())
            .startAt(info.startAt())
            .endAt(info.endAt())
            .liveStatus(info.liveStatus().toMetadata())
            .items(info.items().stream().map(this::toElementItem).toList())
            .build();
    }

    private DisplayElementAdminItem toElementItem(DisplaySectionAdminInfo.Element element) {
        return DisplayElementAdminItem.builder()
            .itemType(element.itemType().toMetadata())
            .media(toMediaItem(element.media()))
            .product(toProductItem(element.product()))
            .caption(element.caption())
            .build();
    }

    private DisplayMediaAdminItem toMediaItem(DisplayMedia media) {
        if (media == null) {
            return null;
        }
        return DisplayMediaAdminItem.builder()
            .mediaType(media.mediaType().toMetadata())
            .pcUrl(media.pcUrl())
            .mobileUrl(media.mobileUrl())
            .pcPosterUrl(media.pcPosterUrl())
            .mobilePosterUrl(media.mobilePosterUrl())
            .altText(media.altText())
            .build();
    }

    private DisplayProductAdminItem toProductItem(DisplaySectionAdminInfo.ProductReference product) {
        if (product == null) {
            return null;
        }
        return DisplayProductAdminItem.builder()
            .productId(String.valueOf(product.productId()))
            .name(product.name())
            .visible(product.visible())
            .build();
    }
}
