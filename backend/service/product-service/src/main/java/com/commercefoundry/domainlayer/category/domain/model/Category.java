package com.commercefoundry.domainlayer.category.domain.model;

import java.util.Objects;

/**
 * 상품 카테고리 한 노드. 계층은 {@link CategoryTree} 가 만든다.
 *
 * @param parentId 상위 카테고리 아이디. 최상위면 null
 * @param displayOrder 같은 상위 안의 노출 순서 (오름차순)
 * @param visible 스토어프론트 노출 여부. 숨기면 하위까지 스토어프론트에서 빠진다
 */
public record Category(long id, Long parentId, String name, int displayOrder, boolean visible) {

    public Category {
        Objects.requireNonNull(name, "name");
    }

    public boolean isRoot() {
        return parentId == null;
    }
}
