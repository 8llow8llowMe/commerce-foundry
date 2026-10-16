package com.commercefoundry.domainlayer.category.domain.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 카테고리 전체 목록으로 만든 계층. 카테고리는 수가 적어 한 번에 모두 읽고 메모리에서 탐색한다 (단건 조회 반복 금지 — N+1).
 *
 * <p>순환(A 의 상위가 B, B 의 상위가 A)이 섞인 데이터를 받아도 무한 루프에 빠지지 않는다 — 모든 탐색이 방문 집합을 쓴다.
 * 순환·깊이 제한({@link #MAX_DEPTH})은 쓰기 쪽(어드민 카테고리 관리)이 저장 전에 막는다. 이 클래스는 읽기 방어만 한다.
 */
public final class CategoryTree {

    /** 카테고리 최대 깊이 (대·중·소). 쓰기 쪽이 저장 전에 검증한다. */
    public static final int MAX_DEPTH = 3;

    private static final Comparator<Category> DISPLAY_ORDER = Comparator.comparingInt(Category::displayOrder).thenComparingLong(Category::id);

    private final Map<Long, Category> categoriesById;
    private final Map<Long, List<Category>> childrenByParentId;
    private final List<Category> roots;

    private CategoryTree(Map<Long, Category> categoriesById, Map<Long, List<Category>> childrenByParentId, List<Category> roots) {
        this.categoriesById = categoriesById;
        this.childrenByParentId = childrenByParentId;
        this.roots = roots;
    }

    public static CategoryTree of(Collection<Category> categories) {
        Map<Long, Category> categoriesById = categories.stream().collect(Collectors.toUnmodifiableMap(Category::id, Function.identity()));

        Map<Long, List<Category>> childrenByParentId = new HashMap<>();
        List<Category> roots = new ArrayList<>();
        for (Category category : categoriesById.values()) {
            if (category.isRoot()) {
                roots.add(category);
            } else {
                childrenByParentId.computeIfAbsent(category.parentId(), parentId -> new ArrayList<>()).add(category);
            }
        }
        roots.sort(DISPLAY_ORDER);
        childrenByParentId.values().forEach(children -> children.sort(DISPLAY_ORDER));

        return new CategoryTree(categoriesById, childrenByParentId, List.copyOf(roots));
    }

    /**
     * 스토어프론트에 보이는 최상위 노드들. 숨긴 노드는 그 하위까지 뺀다. 같은 상위 안에서는 노출 순서 → 아이디 오름차순이다.
     */
    public List<CategoryNode> visibleRoots() {
        return visibleNodes(roots, new HashSet<>());
    }

    /**
     * 자기 자신과 모든 하위 카테고리 아이디. 숨김 여부와 무관하다 — 숨김 판단은 호출하는 쪽이 한다.
     *
     * @return 없는 아이디면 빈 집합
     */
    public Set<Long> subtreeIds(long categoryId) {
        if (!categoriesById.containsKey(categoryId)) {
            return Set.of();
        }

        Set<Long> visited = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(categoryId);
        while (!queue.isEmpty()) {
            long currentId = queue.poll();
            if (!visited.add(currentId)) {
                continue;
            }
            childrenOf(currentId).forEach(child -> queue.add(child.id()));
        }
        return Collections.unmodifiableSet(visited);
    }

    /**
     * 최상위부터 대상 카테고리까지의 경로 (상세 화면 breadcrumb). 숨김 여부와 무관하다.
     *
     * <p>상위가 목록에 없거나 순환이면 거기서 멈춘다 — 경로는 닿은 가장 위 노드부터 시작한다.
     *
     * @return 없는 아이디면 빈 목록
     */
    public List<Category> pathTo(long categoryId) {
        List<Category> path = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Category current = categoriesById.get(categoryId);
        while (current != null && visited.add(current.id())) {
            path.add(current);
            current = current.isRoot() ? null : categoriesById.get(current.parentId());
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }

    /**
     * 스토어프론트 상세 화면 breadcrumb — {@link #pathTo} 에서 숨긴 카테고리만 뺀다. 순서는 그대로다.
     *
     * <p>카테고리 숨김은 "메뉴에서만 숨김" 이다. 숨긴 카테고리 아래 상품도 노출되므로 경로에서는 숨긴 노드만 빠지고
     * 그 하위 노드는 남는다 ({@link #visibleRoots()} 가 숨긴 노드의 하위까지 빼는 것과 다르다).
     *
     * @return 없는 아이디면 빈 목록
     */
    public List<Category> visiblePathTo(long categoryId) {
        return pathTo(categoryId).stream().filter(Category::visible).toList();
    }

    private List<CategoryNode> visibleNodes(List<Category> categories, Set<Long> visited) {
        List<CategoryNode> nodes = new ArrayList<>();
        for (Category category : categories) {
            if (!category.visible() || !visited.add(category.id())) {
                continue;
            }
            nodes.add(new CategoryNode(category, visibleNodes(childrenOf(category.id()), visited)));
        }
        return List.copyOf(nodes);
    }

    private List<Category> childrenOf(long categoryId) {
        return childrenByParentId.getOrDefault(categoryId, List.of());
    }
}
