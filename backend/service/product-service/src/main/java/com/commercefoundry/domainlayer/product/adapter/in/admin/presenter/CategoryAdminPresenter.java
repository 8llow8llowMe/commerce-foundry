package com.commercefoundry.domainlayer.product.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.CategoryAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.CategoryAdminResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.CategoryAdminTreeResponse;
import com.commercefoundry.domainlayer.product.application.info.CategoryAdminInfo;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 고객 트리({@code CategoryWebPresenter})와 DTO 를 공유하지 않는다 — 어드민에만 숨김 노드 · {@code visible} · {@code parentId} 가 있다.
 */
@Component
public class CategoryAdminPresenter {

    public CategoryAdminTreeResponse toTreeResponse(List<CategoryAdminInfo> roots) {
        return CategoryAdminTreeResponse.builder().categories(toItems(roots)).build();
    }

    public CategoryAdminResponse toResponse(CategoryAdminInfo category) {
        return CategoryAdminResponse.builder()
            .categoryId(String.valueOf(category.categoryId()))
            .parentId(idOrNull(category.parentId()))
            .name(category.name())
            .displayOrder(category.displayOrder())
            .visible(category.visible())
            .build();
    }

    private List<CategoryAdminItem> toItems(List<CategoryAdminInfo> nodes) {
        return nodes.stream().map(this::toItem).toList();
    }

    private CategoryAdminItem toItem(CategoryAdminInfo node) {
        return CategoryAdminItem.builder()
            .categoryId(String.valueOf(node.categoryId()))
            .parentId(idOrNull(node.parentId()))
            .name(node.name())
            .displayOrder(node.displayOrder())
            .visible(node.visible())
            .children(toItems(node.children()))
            .build();
    }

    private static String idOrNull(Long id) {
        return id == null ? null : String.valueOf(id);
    }
}
