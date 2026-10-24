package com.commercefoundry.domainlayer.display.application.mapper;

import com.commercefoundry.domainlayer.display.adapter.out.persistence.entity.DisplayItemEntity;
import com.commercefoundry.domainlayer.display.adapter.out.persistence.entity.DisplaySectionEntity;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItem;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItemType;
import com.commercefoundry.domainlayer.display.domain.model.DisplayMedia;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySection;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * 전시 Entity ↔ Domain. 아이템의 미디어는 domain 에서 한 값({@link DisplayMedia})이고 행에서는 평평한 칸이라 직접 옮긴다.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DisplaySectionMapper {

    DisplaySection toDomain(DisplaySectionEntity entity);

    List<DisplaySection> toDomains(List<DisplaySectionEntity> entities);

    /** 새 행 INSERT 용. 감사 시각은 INSERT 때 리스너가 채운다. */
    DisplaySectionEntity toEntity(DisplaySection section);

    default DisplayItem toDomain(DisplayItemEntity entity) {
        DisplayMedia media = entity.getItemType() == DisplayItemType.MEDIA
            ? new DisplayMedia(entity.getMediaType(), entity.getPcUrl(), entity.getMobileUrl(), entity.getPcPosterUrl(), entity.getMobilePosterUrl(), entity.getAltText())
            : null;
        return new DisplayItem(
            entity.getId(), entity.getSectionId(), entity.getDisplayOrder(), entity.getItemType(), media, entity.getProductId(), entity.getCaption()
        );
    }

    default List<DisplayItem> toItemDomains(List<DisplayItemEntity> entities) {
        return entities.stream().map(this::toDomain).toList();
    }

    /** 새 행 INSERT 용. 미디어가 없는 칸은 null 이다. */
    default DisplayItemEntity toEntity(DisplayItem item) {
        DisplayMedia media = item.media();
        return DisplayItemEntity.builder()
            .id(item.id())
            .sectionId(item.sectionId())
            .displayOrder(item.displayOrder())
            .itemType(item.itemType())
            .mediaType(media == null ? null : media.mediaType())
            .pcUrl(media == null ? null : media.pcUrl())
            .mobileUrl(media == null ? null : media.mobileUrl())
            .pcPosterUrl(media == null ? null : media.pcPosterUrl())
            .mobilePosterUrl(media == null ? null : media.mobilePosterUrl())
            .altText(media == null ? null : media.altText())
            .productId(item.productId())
            .caption(item.caption())
            .build();
    }
}
