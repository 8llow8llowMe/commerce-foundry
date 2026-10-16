package com.commercefoundry.domainlayer.category.adapter.out.persistence;

import com.commercefoundry.domainlayer.category.adapter.out.persistence.repository.CategoryRepository;
import com.commercefoundry.domainlayer.category.application.mapper.CategoryMapper;
import com.commercefoundry.domainlayer.category.application.port.out.CategoryRepositoryPort;
import com.commercefoundry.domainlayer.category.domain.model.Category;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CategoryRepositoryAdapter implements CategoryRepositoryPort {

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    @Override
    public List<Category> findAll() {
        return categoryMapper.toDomains(categoryRepository.findAll());
    }
}
