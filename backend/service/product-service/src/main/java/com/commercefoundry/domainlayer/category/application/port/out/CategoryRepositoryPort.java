package com.commercefoundry.domainlayer.category.application.port.out;

import com.commercefoundry.domainlayer.category.domain.model.Category;
import java.util.List;

public interface CategoryRepositoryPort {

    /** 숨김 포함 전체. 계층은 {@code CategoryTree} 가 메모리에서 만든다. */
    List<Category> findAll();
}
