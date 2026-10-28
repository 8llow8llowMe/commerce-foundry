package com.commercefoundry.domainlayer.look.application.mapper;

import com.commercefoundry.domainlayer.look.adapter.out.persistence.entity.LookEntity;
import com.commercefoundry.domainlayer.look.adapter.out.persistence.entity.LookItemEntity;
import com.commercefoundry.domainlayer.look.domain.model.Look;
import com.commercefoundry.domainlayer.look.domain.model.LookImage;
import com.commercefoundry.domainlayer.look.domain.model.LookItem;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * 룩 Entity ↔ Domain. 대표 이미지는 domain 에서 한 값({@link LookImage})이고 행에서는 평평한 칸이라 직접 옮긴다.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface LookMapper {

    default Look toDomain(LookEntity entity) {
        LookImage image = new LookImage(entity.getImageUrl(), entity.getImageWidth(), entity.getImageHeight(), entity.getImageAltText());
        return new Look(entity.getId(), entity.getTitle(), entity.getDescription(), image, entity.isVisible(), entity.getDisplayOrder());
    }

    default List<Look> toDomains(List<LookEntity> entities) {
        return entities.stream().map(this::toDomain).toList();
    }

    /** 새 행 INSERT 용. 감사 시각은 INSERT 때 리스너가 채운다. */
    default LookEntity toEntity(Look look) {
        LookImage image = look.image();
        return LookEntity.builder()
            .id(look.id())
            .title(look.title())
            .description(look.description())
            .imageUrl(image.url())
            .imageWidth(image.width())
            .imageHeight(image.height())
            .imageAltText(image.altText())
            .visible(look.visible())
            .displayOrder(look.displayOrder())
            .build();
    }

    LookItem toDomain(LookItemEntity entity);

    List<LookItem> toItemDomains(List<LookItemEntity> entities);

    /** 새 행 INSERT 용. 감사 시각은 INSERT 때 리스너가 채운다. */
    LookItemEntity toEntity(LookItem item);
}
