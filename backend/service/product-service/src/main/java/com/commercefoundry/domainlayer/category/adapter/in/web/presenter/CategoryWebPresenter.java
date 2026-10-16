package com.commercefoundry.domainlayer.category.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.category.adapter.in.web.dto.item.CategoryItem;
import com.commercefoundry.domainlayer.category.adapter.in.web.dto.response.CategoryTreeResponse;
import com.commercefoundry.domainlayer.category.application.info.CategoryNodeInfo;
import com.commercefoundry.domainlayer.category.application.info.CategoryTreeInfo;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CategoryWebPresenter {

    public CategoryTreeResponse toResponse(CategoryTreeInfo info) {
        return CategoryTreeResponse.builder()
            .categories(toItems(info.categories()))
            .build();
    }

    private List<CategoryItem> toItems(List<CategoryNodeInfo> nodes) {
        return nodes.stream().map(this::toItem).toList();
    }

    private CategoryItem toItem(CategoryNodeInfo node) {
        return CategoryItem.builder()
            .categoryId(String.valueOf(node.categoryId()))
            .name(node.name())
            .displayOrder(node.displayOrder())
            .children(toItems(node.children()))
            .build();
    }
}
