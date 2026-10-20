package com.commercefoundry.domainlayer.category.application.mapper;

import com.commercefoundry.domainlayer.category.adapter.out.persistence.entity.CategoryEntity;
import com.commercefoundry.domainlayer.category.domain.model.Category;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CategoryMapper {

    Category toDomain(CategoryEntity entity);

    List<Category> toDomains(List<CategoryEntity> entities);

    /** 새 행 INSERT 용. 감사 시각은 INSERT 때 리스너가 채운다. */
    CategoryEntity toEntity(Category category);
}
