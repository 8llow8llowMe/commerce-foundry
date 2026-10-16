package com.commercefoundry.domainlayer.category.application.info;

import java.util.List;

/**
 * @param children 보이는 하위 노드. 노출 순서 → 아이디 오름차순이다
 */
public record CategoryNodeInfo(long categoryId, String name, int displayOrder, List<CategoryNodeInfo> children) {

    public CategoryNodeInfo {
        children = List.copyOf(children);
    }
}
