package com.commercefoundry.domainlayer.category.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.commercefoundry.domainlayer.category.application.info.CategoryNodeInfo;
import com.commercefoundry.domainlayer.category.application.info.CategoryTreeInfo;
import com.commercefoundry.domainlayer.category.application.port.out.CategoryRepositoryPort;
import com.commercefoundry.domainlayer.category.domain.model.Category;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoryProcessorTest {

    @Mock
    private CategoryRepositoryPort categoryRepositoryPort;

    @InjectMocks
    private CategoryProcessor categoryProcessor;

    @Test
    @DisplayName("보이는 트리를 Info 로 바꾼다 — 숨긴 노드는 하위까지 빠지고 노출 순서 → 아이디 오름차순이다")
    void visibleTree() {
        given(categoryRepositoryPort.findAll()).willReturn(List.of(
            new Category(1, null, "상의", 2, true),
            new Category(2, null, "하의", 1, true),
            new Category(11, 1L, "저고리", 0, true),
            new Category(12, 1L, "숨김", 0, false),
            new Category(121, 12L, "숨김-하위", 0, true)
        ));

        CategoryTreeInfo tree = categoryProcessor.getVisibleTree();

        assertThat(tree.categories()).containsExactly(
            new CategoryNodeInfo(2, "하의", 1, List.of()),
            new CategoryNodeInfo(1, "상의", 2, List.of(new CategoryNodeInfo(11, "저고리", 0, List.of())))
        );
    }

    @Test
    @DisplayName("카테고리가 없으면 빈 트리다")
    void emptyTree() {
        given(categoryRepositoryPort.findAll()).willReturn(List.of());

        assertThat(categoryProcessor.getVisibleTree().categories()).isEmpty();
    }

    //  상의(1) ─ 저고리(11) ─ 숨김(111, 숨김) ─ 숨김-하위(1111)
    private static final List<Category> CHAIN = List.of(
        new Category(1, null, "상의", 0, true),
        new Category(11, 1L, "저고리", 0, true),
        new Category(111, 11L, "숨김", 0, false),
        new Category(1111, 111L, "숨김-하위", 0, true)
    );

    @Test
    @DisplayName("subtreeIdsOf 는 숨김과 무관하게 자기 자신과 모든 하위를 낸다 — 숨긴 하위에만 진열된 상품도 목록에 나온다")
    void subtreeIdsIncludeHidden() {
        given(categoryRepositoryPort.findAll()).willReturn(CHAIN);

        assertThat(categoryProcessor.subtreeIdsOf(11)).containsExactlyInAnyOrder(11L, 111L, 1111L);
    }

    @Test
    @DisplayName("subtreeIdsOf 는 숨긴 카테고리를 직접 줘도 그 하위를 낸다")
    void subtreeIdsOfHiddenCategory() {
        given(categoryRepositoryPort.findAll()).willReturn(CHAIN);

        assertThat(categoryProcessor.subtreeIdsOf(111)).containsExactlyInAnyOrder(111L, 1111L);
    }

    @Test
    @DisplayName("subtreeIdsOf 는 없는 아이디면 빈 집합이다")
    void subtreeIdsOfUnknown() {
        given(categoryRepositoryPort.findAll()).willReturn(CHAIN);

        assertThat(categoryProcessor.subtreeIdsOf(999)).isEmpty();
    }

    @Test
    @DisplayName("visiblePathOf 는 최상위부터의 경로에서 숨긴 카테고리만 뺀다")
    void visiblePathDropsHidden() {
        given(categoryRepositoryPort.findAll()).willReturn(CHAIN);

        assertThat(categoryProcessor.visiblePathOf(1111)).extracting(Category::id).containsExactly(1L, 11L, 1111L);
    }

    @Test
    @DisplayName("visiblePathOf 는 없는 아이디(대표 카테고리 데이터 오류)면 빈 목록이다")
    void visiblePathOfUnknown() {
        given(categoryRepositoryPort.findAll()).willReturn(CHAIN);

        assertThat(categoryProcessor.visiblePathOf(999)).isEmpty();
    }
}
