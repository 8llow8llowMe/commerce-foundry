package com.commercefoundry.domainlayer.category.adapter.in.web.presenter;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.category.adapter.in.web.dto.item.CategoryItem;
import com.commercefoundry.domainlayer.category.application.info.CategoryNodeInfo;
import com.commercefoundry.domainlayer.category.application.info.CategoryTreeInfo;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CategoryWebPresenterTest {

    private final CategoryWebPresenter presenter = new CategoryWebPresenter();

    @Test
    @DisplayName("하위까지 아이디를 문자열로 바꾸고 순서를 그대로 둔다")
    void nestedTree() {
        CategoryTreeInfo info = new CategoryTreeInfo(List.of(
            new CategoryNodeInfo(4611686018427387904L, "상의", 1, List.of(new CategoryNodeInfo(102, "저고리", 1, List.of()))),
            new CategoryNodeInfo(201, "하의", 2, List.of())
        ));

        assertThat(presenter.toResponse(info).categories()).containsExactly(
            CategoryItem.builder().categoryId("4611686018427387904").name("상의").displayOrder(1)
                .children(List.of(CategoryItem.builder().categoryId("102").name("저고리").displayOrder(1).children(List.of()).build()))
                .build(),
            CategoryItem.builder().categoryId("201").name("하의").displayOrder(2).children(List.of()).build()
        );
    }

    @Test
    @DisplayName("카테고리가 없으면 categories: [] 다")
    void empty() {
        assertThat(presenter.toResponse(new CategoryTreeInfo(List.of())).categories()).isEmpty();
    }
}
