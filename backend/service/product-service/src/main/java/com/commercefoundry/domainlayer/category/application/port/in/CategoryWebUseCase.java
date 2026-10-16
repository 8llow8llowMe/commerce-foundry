package com.commercefoundry.domainlayer.category.application.port.in;

import com.commercefoundry.domainlayer.category.adapter.in.web.dto.response.CategoryTreeResponse;

public interface CategoryWebUseCase {

    /** 스토어프론트 메뉴용 카테고리 트리. 숨긴 카테고리는 그 하위까지 빠진다. */
    CategoryTreeResponse getCategoryTree();
}
