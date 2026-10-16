package com.commercefoundry.domainlayer.category.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class CategoryTreeTest {

    //  상의(1, 순서 2)                하의(2, 순서 1)          숨김루트(3, 숨김)
    //   ├ 셔츠(11, 순서 1)              │                      └ 숨김루트-하위(31)
    //   │  └ 반팔(111)                  │
    //   ├ 숨김상의(12, 순서 0, 숨김)     │
    //   │  └ 숨김상의-하위(121)          │
    //   └ 블라우스(13, 순서 1)  ← 셔츠와 순서가 같아 아이디로 정렬
    private static final List<Category> CATEGORIES = List.of(
        category(1, null, "상의", 2, true),
        category(2, null, "하의", 1, true),
        category(3, null, "숨김루트", 0, false),
        category(11, 1L, "셔츠", 1, true),
        category(12, 1L, "숨김상의", 0, false),
        category(13, 1L, "블라우스", 1, true),
        category(31, 3L, "숨김루트-하위", 0, true),
        category(111, 11L, "반팔", 0, true),
        category(121, 12L, "숨김상의-하위", 0, true)
    );

    private final CategoryTree tree = CategoryTree.of(CATEGORIES);

    @Nested
    @DisplayName("visibleRoots")
    class VisibleRoots {

        @Test
        @DisplayName("숨긴 노드는 그 하위까지 빠지고, 같은 상위 안에서 노출 순서 → 아이디 오름차순이다")
        void excludesHiddenSubtreesAndSorts() {
            List<CategoryNode> roots = tree.visibleRoots();

            assertThat(roots).extracting(node -> node.category().id()).containsExactly(2L, 1L);

            CategoryNode top = roots.get(1);
            assertThat(top.children()).extracting(node -> node.category().id()).containsExactly(11L, 13L);
            assertThat(top.children().get(0).children()).extracting(node -> node.category().id()).containsExactly(111L);
            assertThat(top.children().get(1).children()).isEmpty();
            assertThat(roots.get(0).children()).isEmpty();
        }

        @Test
        @DisplayName("카테고리가 없으면 빈 목록이다")
        void emptyTree() {
            assertThat(CategoryTree.of(List.of()).visibleRoots()).isEmpty();
        }
    }

    @Nested
    @DisplayName("subtreeIds")
    class SubtreeIds {

        @Test
        @DisplayName("자기 자신과 모든 하위를 담는다 — 숨김 여부와 무관하다")
        void includesSelfAndHiddenDescendants() {
            assertThat(tree.subtreeIds(1)).containsExactlyInAnyOrder(1L, 11L, 12L, 13L, 111L, 121L);
            assertThat(tree.subtreeIds(3)).containsExactlyInAnyOrder(3L, 31L);
        }

        @Test
        @DisplayName("리프면 자기 자신만이다")
        void leaf() {
            assertThat(tree.subtreeIds(111)).containsExactly(111L);
        }

        @Test
        @DisplayName("없는 아이디면 빈 집합이다")
        void unknownId() {
            assertThat(tree.subtreeIds(999)).isEmpty();
        }
    }

    @Nested
    @DisplayName("pathTo")
    class PathTo {

        @Test
        @DisplayName("최상위부터 대상까지 순서대로 낸다")
        void rootToLeaf() {
            assertThat(tree.pathTo(111)).extracting(Category::id).containsExactly(1L, 11L, 111L);
            assertThat(tree.pathTo(1)).extracting(Category::id).containsExactly(1L);
        }

        @Test
        @DisplayName("숨김 여부와 무관하다")
        void ignoresVisibility() {
            assertThat(tree.pathTo(121)).extracting(Category::id).containsExactly(1L, 12L, 121L);
        }

        @Test
        @DisplayName("없는 아이디면 빈 목록이다")
        void unknownId() {
            assertThat(tree.pathTo(999)).isEmpty();
        }

        @Test
        @DisplayName("상위가 목록에 없으면 닿은 노드부터 시작한다")
        void orphan() {
            CategoryTree orphanTree = CategoryTree.of(List.of(category(51, 999L, "고아", 0, true), category(52, 51L, "고아-하위", 0, true)));

            assertThat(orphanTree.pathTo(52)).extracting(Category::id).containsExactly(51L, 52L);
            assertThat(orphanTree.visibleRoots()).isEmpty();
        }
    }

    @Nested
    @DisplayName("visiblePathTo")
    class VisiblePathTo {

        @Test
        @DisplayName("모두 보이면 pathTo 와 같다")
        void allVisible() {
            assertThat(tree.visiblePathTo(111)).extracting(Category::id).containsExactly(1L, 11L, 111L);
        }

        @Test
        @DisplayName("숨긴 중간 노드만 빠지고 그 하위와 상위는 순서대로 남는다 — 숨김은 메뉴에서만 뺀다")
        void dropsHiddenMiddleNodeOnly() {
            assertThat(tree.visiblePathTo(121)).extracting(Category::id).containsExactly(1L, 121L);
        }

        @Test
        @DisplayName("숨긴 최상위만 빠진다")
        void dropsHiddenRoot() {
            assertThat(tree.visiblePathTo(31)).extracting(Category::id).containsExactly(31L);
        }

        @Test
        @DisplayName("대상이 숨김이면 대상도 빠진다")
        void dropsHiddenTarget() {
            assertThat(tree.visiblePathTo(12)).extracting(Category::id).containsExactly(1L);
            assertThat(tree.visiblePathTo(3)).isEmpty();
        }

        @Test
        @DisplayName("없는 아이디(대표 카테고리 데이터 오류)면 빈 목록이다")
        void unknownId() {
            assertThat(tree.visiblePathTo(999)).isEmpty();
        }
    }

    @Nested
    @DisplayName("순환 데이터")
    class Cycle {

        // 41 ↔ 42 가 서로를 상위로 가리키고, 43 은 자기 자신을 상위로 가리킨다
        private final CategoryTree cyclicTree = CategoryTree.of(List.of(
            category(1, null, "정상", 0, true),
            category(41, 42L, "순환-A", 0, true),
            category(42, 41L, "순환-B", 0, true),
            category(43, 43L, "자기참조", 0, true)
        ));

        @Test
        @Timeout(value = 1, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("subtreeIds 가 무한 루프에 빠지지 않는다")
        void subtreeIdsTerminates() {
            assertThat(cyclicTree.subtreeIds(41)).containsExactlyInAnyOrder(41L, 42L);
            assertThat(cyclicTree.subtreeIds(43)).containsExactly(43L);
        }

        @Test
        @Timeout(value = 1, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("pathTo 가 무한 루프에 빠지지 않고 대상으로 끝난다")
        void pathToTerminates() {
            assertThat(cyclicTree.pathTo(41)).extracting(Category::id).containsExactly(42L, 41L);
            assertThat(cyclicTree.pathTo(43)).extracting(Category::id).containsExactly(43L);
        }

        @Test
        @Timeout(value = 1, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("최상위에서 닿지 않는 순환 노드는 visibleRoots 에 나오지 않는다")
        void visibleRootsSkipsCycles() {
            assertThat(cyclicTree.visibleRoots()).extracting(node -> node.category().id()).containsExactly(1L);
        }
    }

    private static Category category(long id, Long parentId, String name, int displayOrder, boolean visible) {
        return new Category(id, parentId, name, displayOrder, visible);
    }
}
