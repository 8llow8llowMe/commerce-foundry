package com.commercefoundry.domainlayer.category.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.category.adapter.out.persistence.entity.CategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<CategoryEntity, Long> {

}
