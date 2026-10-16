package com.commercefoundry.domainlayer.category.application.info;

import java.util.List;

/**
 * 스토어프론트에 보이는 카테고리 트리.
 *
 * @param categories 최상위 노드. 같은 상위 안에서 노출 순서 → 아이디 오름차순이다
 */
public record CategoryTreeInfo(List<CategoryNodeInfo> categories) {

    public CategoryTreeInfo {
        categories = List.copyOf(categories);
    }
}
