package com.commercefoundry.domainlayer.category.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        @DisplayName("숨긴 중간 노드에서 끊긴다 — 그 아래 보이는 하위는 메뉴에 없으므로 빠진다 (#164)")
        void stopsAtHiddenMiddleNode() {
            assertThat(tree.visiblePathTo(121)).extracting(Category::id).containsExactly(1L);
        }

        @Test
        @DisplayName("최상위가 숨김이면 그 아래 보이는 하위까지 빈 목록이다 (#164)")
        void emptyUnderHiddenRoot() {
            assertThat(tree.visiblePathTo(31)).isEmpty();
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

        @Test
        @DisplayName("상위가 목록에 없어 최상위에서 닿지 않는 노드는 메뉴에 없으므로 빈 목록이다")
        void emptyForOrphan() {
            CategoryTree orphanTree = CategoryTree.of(List.of(category(51, 999L, "고아", 0, true), category(52, 51L, "고아-하위", 0, true)));

            assertThat(orphanTree.visiblePathTo(52)).isEmpty();
        }

        @Test
        @DisplayName("메뉴와 같은 규칙이다 — 모든 노드에 대해 breadcrumb 은 경로 중 메뉴에 있는 가장 긴 앞부분이다 (정상 · 고아 · 순환 트리)")
        void sameRuleAsMenu() {
            assertBreadcrumbIsLongestMenuPrefix(tree, CATEGORIES);

            List<Category> orphanAndCycle = List.of(
                category(1, null, "정상", 0, true),
                category(51, 999L, "고아", 0, true),
                category(52, 51L, "고아-하위", 0, true),
                category(41, 42L, "순환-A", 0, true),
                category(42, 41L, "순환-B", 0, true));
            assertBreadcrumbIsLongestMenuPrefix(CategoryTree.of(orphanAndCycle), orphanAndCycle);
        }

        /** 메뉴에 나오는 아이디로 경로의 앞부분을 자른 것과 breadcrumb 이 같아야 한다 — 숨김 판정이 갈라지면 실패한다. */
        private static void assertBreadcrumbIsLongestMenuPrefix(CategoryTree target, List<Category> categories) {
            Set<Long> menuIds = new HashSet<>();
            collectIds(target.visibleRoots(), menuIds);

            for (Category category : categories) {
                List<Long> breadcrumb = target.visiblePathTo(category.id()).stream().map(Category::id).toList();
                List<Long> longestMenuPrefix = target.pathTo(category.id()).stream().map(Category::id).takeWhile(menuIds::contains).toList();

                assertThat(breadcrumb).as("category %d", category.id()).isEqualTo(longestMenuPrefix);
            }
        }

        private static void collectIds(List<CategoryNode> nodes, Set<Long> ids) {
            for (CategoryNode node : nodes) {
                ids.add(node.category().id());
                collectIds(node.children(), ids);
            }
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

        @Test
        @Timeout(value = 1, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("최상위에서 닿지 않는 순환 노드의 breadcrumb 은 빈 목록이다 — 메뉴와 같다")
        void visiblePathToIsEmptyForCycles() {
            assertThat(cyclicTree.visiblePathTo(41)).isEmpty();
            assertThat(cyclicTree.visiblePathTo(43)).isEmpty();
        }

        @Test
        @Timeout(value = 1, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        @DisplayName("allRoots · placementViolationOf 도 무한 루프에 빠지지 않는다")
        void adminOperationsTerminate() {
            assertThat(cyclicTree.allRoots()).extracting(node -> node.category().id()).containsExactly(1L);
            assertThat(cyclicTree.placementViolationOf(41L, 1L)).isEmpty();
            assertThat(cyclicTree.placementViolationOf(41L, 42L)).contains(CategoryPlacementViolation.CYCLE);
            assertThat(cyclicTree.placementViolationOf(43L, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("allRoots · find · hasChildren (어드민)")
    class Admin {

        @Test
        @DisplayName("숨긴 노드와 그 하위도 담고, 같은 상위 안에서 노출 순서 → 아이디 오름차순이다")
        void includesHidden() {
            List<CategoryNode> roots = tree.allRoots();

            assertThat(roots).extracting(node -> node.category().id()).containsExactly(3L, 2L, 1L);
            assertThat(roots.get(0).children()).extracting(node -> node.category().id()).containsExactly(31L);
            CategoryNode top = roots.get(2);
            assertThat(top.children()).extracting(node -> node.category().id()).containsExactly(12L, 11L, 13L);
            assertThat(top.children().get(0).children()).extracting(node -> node.category().id()).containsExactly(121L);
        }

        @Test
        @DisplayName("find 는 숨김과 무관하게 찾고, 없으면 빈 값이다")
        void find() {
            assertThat(tree.find(12)).map(Category::name).contains("숨김상의");
            assertThat(tree.find(999)).isEmpty();
        }

        @Test
        @DisplayName("hasChildren 은 숨긴 하위도 센다")
        void hasChildren() {
            assertThat(tree.hasChildren(1)).isTrue();
            assertThat(tree.hasChildren(3)).isTrue();
            assertThat(tree.hasChildren(111)).isFalse();
            assertThat(tree.hasChildren(999)).isFalse();
        }
    }

    @Nested
    @DisplayName("placementViolationOf — 새로 만들기")
    class PlacementOfNew {

        @Test
        @DisplayName("최상위 · 1단계 아래 · 2단계 아래(3단계가 된다)는 둘 수 있다")
        void allowedUpToMaxDepth() {
            assertThat(tree.placementViolationOf(null, null)).isEmpty();
            assertThat(tree.placementViolationOf(null, 1L)).isEmpty();
            assertThat(tree.placementViolationOf(null, 11L)).isEmpty();
        }

        @Test
        @DisplayName("3단계 아래에 만들면 깊이 초과다")
        void depthExceeded() {
            assertThat(tree.placementViolationOf(null, 111L)).contains(CategoryPlacementViolation.DEPTH_EXCEEDED);
        }

        @Test
        @DisplayName("없는 상위면 PARENT_NOT_FOUND 다")
        void parentNotFound() {
            assertThat(tree.placementViolationOf(null, 999L)).contains(CategoryPlacementViolation.PARENT_NOT_FOUND);
        }

        @Test
        @DisplayName("숨긴 상위 아래에도 만들 수 있다")
        void hiddenParent() {
            assertThat(tree.placementViolationOf(null, 12L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("placementViolationOf — 상위 바꾸기")
    class PlacementOfMove {

        @Test
        @DisplayName("하위를 거느린 카테고리를 옮기면 하위 높이까지 깊이를 센다")
        void countsSubtreeHeight() {
            // 셔츠(11) 는 반팔(111)을 거느려 높이 2 — 1단계 아래면 3단계, 2단계 아래면 4단계다
            assertThat(tree.placementViolationOf(11L, 2L)).isEmpty();
            assertThat(tree.placementViolationOf(11L, 13L)).contains(CategoryPlacementViolation.DEPTH_EXCEEDED);
            // 상의(1) 는 높이 3 — 어디 아래로도 옮길 수 없고 최상위에는 둘 수 있다
            assertThat(tree.placementViolationOf(1L, 2L)).contains(CategoryPlacementViolation.DEPTH_EXCEEDED);
            assertThat(tree.placementViolationOf(1L, null)).isEmpty();
        }

        @Test
        @DisplayName("리프를 최상위로 올리거나 다른 1단계 아래로 옮길 수 있다")
        void leafMoves() {
            assertThat(tree.placementViolationOf(111L, null)).isEmpty();
            assertThat(tree.placementViolationOf(111L, 3L)).isEmpty();
            assertThat(tree.placementViolationOf(12L, 3L)).isEmpty();
        }

        @Test
        @DisplayName("자기 자신이나 자기 하위(숨긴 하위 포함)를 상위로 지정하면 순환이다 — 깊이보다 먼저 본다")
        void cycle() {
            assertThat(tree.placementViolationOf(11L, 11L)).contains(CategoryPlacementViolation.CYCLE);
            assertThat(tree.placementViolationOf(11L, 111L)).contains(CategoryPlacementViolation.CYCLE);
            assertThat(tree.placementViolationOf(1L, 121L)).contains(CategoryPlacementViolation.CYCLE);
        }

        @Test
        @DisplayName("없는 상위면 순환 · 깊이보다 먼저 PARENT_NOT_FOUND 다")
        void parentNotFound() {
            assertThat(tree.placementViolationOf(1L, 999L)).contains(CategoryPlacementViolation.PARENT_NOT_FOUND);
        }
    }

    private static Category category(long id, Long parentId, String name, int displayOrder, boolean visible) {
        return new Category(id, parentId, name, displayOrder, visible);
    }
}
