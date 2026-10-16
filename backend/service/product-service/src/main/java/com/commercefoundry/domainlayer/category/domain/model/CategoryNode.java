package com.commercefoundry.domainlayer.category.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * {@link CategoryTree#visibleRoots()} 가 내는 트리 노드. 하위 노드는 노출 순서대로 들어 있다.
 */
public record CategoryNode(Category category, List<CategoryNode> children) {

    public CategoryNode {
        Objects.requireNonNull(category, "category");
        children = List.copyOf(children);
    }
}
