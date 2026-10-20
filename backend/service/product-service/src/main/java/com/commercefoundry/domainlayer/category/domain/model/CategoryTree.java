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
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 카테고리 전체 목록으로 만든 계층. 카테고리는 수가 적어 한 번에 모두 읽고 메모리에서 탐색한다 (단건 조회 반복 금지 — N+1).
 *
 * <p>순환(A 의 상위가 B, B 의 상위가 A)이 섞인 데이터를 받아도 무한 루프에 빠지지 않는다 — 모든 탐색이 방문 집합을 쓴다.
 * 순환·깊이 제한({@link #MAX_DEPTH})은 쓰기 쪽(어드민 카테고리 관리)이 저장 전에 {@link #placementViolationOf} 로 판정해 막는다.
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
        return nodes(roots, new HashSet<>(), true);
    }

    /**
     * 어드민 트리의 최상위 노드들 — 숨긴 노드와 그 하위도 담는다. 정렬은 {@link #visibleRoots()} 와 같다.
     *
     * <p>상위가 목록에 없는 노드 · 최상위에서 닿지 않는 순환 노드는 나오지 않는다. 쓰기 쪽이 그런 데이터를 만들지 않는다
     * (하위가 있으면 삭제를 막고, 순환을 막는다).
     */
    public List<CategoryNode> allRoots() {
        return nodes(roots, new HashSet<>(), false);
    }

    public Optional<Category> find(long categoryId) {
        return Optional.ofNullable(categoriesById.get(categoryId));
    }

    /** 숨김 여부와 무관하게 바로 아래 카테고리가 하나라도 있는가. */
    public boolean hasChildren(long categoryId) {
        return !childrenOf(categoryId).isEmpty();
    }

    /**
     * 카테고리를 {@code parentId} 아래(null 이면 최상위)에 둘 수 있는지 판정한다 — 새로 만들 때와 상위를 바꿀 때 쓴다.
     *
     * <ul>
     *   <li>상위가 없으면 {@link CategoryPlacementViolation#PARENT_NOT_FOUND}</li>
     *   <li>자기 자신이나 자기 하위가 상위면 {@link CategoryPlacementViolation#CYCLE}</li>
     *   <li>상위의 깊이 + 옮기는 하위 트리의 높이가 {@link #MAX_DEPTH} 를 넘으면 {@link CategoryPlacementViolation#DEPTH_EXCEEDED} —
     *       하위를 거느린 카테고리를 옮기면 하위도 같이 내려간다</li>
     * </ul>
     *
     * @param categoryId 옮길 카테고리. 새로 만들면 null 이다 (높이 1)
     * @param parentId 새 상위. 최상위면 null
     * @return 둘 수 있으면 빈 값
     */
    public Optional<CategoryPlacementViolation> placementViolationOf(Long categoryId, Long parentId) {
        int height = categoryId == null ? 1 : subtreeHeight(categoryId);
        if (parentId == null) {
            return height > MAX_DEPTH ? Optional.of(CategoryPlacementViolation.DEPTH_EXCEEDED) : Optional.empty();
        }
        if (!categoriesById.containsKey(parentId)) {
            return Optional.of(CategoryPlacementViolation.PARENT_NOT_FOUND);
        }
        if (categoryId != null && subtreeIds(categoryId).contains(parentId)) {
            return Optional.of(CategoryPlacementViolation.CYCLE);
        }
        return pathTo(parentId).size() + height > MAX_DEPTH ? Optional.of(CategoryPlacementViolation.DEPTH_EXCEEDED) : Optional.empty();
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

    /** 대상 카테고리를 꼭대기로 한 하위 트리의 층 수 — 리프면 1, 없는 아이디도 1(새 카테고리와 같다). 순환 데이터에서도 끝난다. */
    private int subtreeHeight(long categoryId) {
        Set<Long> visited = new HashSet<>();
        List<Long> level = List.of(categoryId);
        int height = 0;
        while (!level.isEmpty()) {
            height++;
            List<Long> next = new ArrayList<>();
            for (long id : level) {
                if (visited.add(id)) {
                    childrenOf(id).forEach(child -> next.add(child.id()));
                }
            }
            next.removeIf(visited::contains);
            level = next;
        }
        return height;
    }

    /** {@code visibleOnly} 면 숨긴 노드를 그 하위까지 뺀다. 방문 집합으로 순환 데이터에서도 끝난다. */
    private List<CategoryNode> nodes(List<Category> categories, Set<Long> visited, boolean visibleOnly) {
        List<CategoryNode> nodes = new ArrayList<>();
        for (Category category : categories) {
            if ((visibleOnly && !category.visible()) || !visited.add(category.id())) {
                continue;
            }
            nodes.add(new CategoryNode(category, nodes(childrenOf(category.id()), visited, visibleOnly)));
        }
        return List.copyOf(nodes);
    }

    private List<Category> childrenOf(long categoryId) {
        return childrenByParentId.getOrDefault(categoryId, List.of());
    }
}
