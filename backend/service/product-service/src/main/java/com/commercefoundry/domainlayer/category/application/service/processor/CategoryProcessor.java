package com.commercefoundry.domainlayer.category.application.service.processor;

import com.commercefoundry.domainlayer.category.application.info.CategoryNodeInfo;
import com.commercefoundry.domainlayer.category.application.info.CategoryTreeInfo;
import com.commercefoundry.domainlayer.category.application.port.out.CategoryRepositoryPort;
import com.commercefoundry.domainlayer.category.domain.model.Category;
import com.commercefoundry.domainlayer.category.domain.model.CategoryNode;
import com.commercefoundry.domainlayer.category.domain.model.CategoryTree;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 카테고리 읽기. 카테고리는 수가 적어 전체를 한 번 읽고 {@link CategoryTree} 로 메모리에서 탐색한다.
 *
 * <p>같은 서비스의 다른 컨텍스트(상품)는 용도별 메서드({@link #subtreeIdsOf} · {@link #visiblePathOf})만 부른다 — 숨김 같은 조회 규칙은
 * 이 컨텍스트가 정한다. {@link CategoryRepositoryPort} 를 직접 주입하지 않는다 ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙).
 */
@Component
@RequiredArgsConstructor
public class CategoryProcessor {

    private final CategoryRepositoryPort categoryRepositoryPort;

    /** 스토어프론트 메뉴 — 숨긴 노드는 그 하위까지 뺀다 ({@link CategoryTree#visibleRoots()}). */
    public CategoryTreeInfo getVisibleTree() {
        return new CategoryTreeInfo(toInfos(loadTree().visibleRoots()));
    }

    /**
     * 상품 목록 필터 — 자기 자신과 모든 하위 카테고리 아이디. <b>숨김 여부와 무관하다</b>: 카테고리 숨김은 메뉴에서만 빼고 상품 노출을 막지 않는다.
     *
     * @return 없는 아이디면 빈 집합
     */
    public Set<Long> subtreeIdsOf(long categoryId) {
        return loadTree().subtreeIds(categoryId);
    }

    /**
     * 상품 상세 breadcrumb — 최상위부터 대상까지의 경로에서 숨긴 카테고리만 뺀다. 순서는 그대로다.
     *
     * @return 없는 아이디(대표 카테고리 데이터 오류)면 빈 목록
     */
    public List<Category> visiblePathOf(long categoryId) {
        return loadTree().visiblePathTo(categoryId);
    }

    /** 숨김 포함 전체 카테고리로 만든 트리. 숨김 판단은 용도별 메서드가 트리의 메서드로 한다. */
    private CategoryTree loadTree() {
        return CategoryTree.of(categoryRepositoryPort.findAll());
    }

    private static List<CategoryNodeInfo> toInfos(List<CategoryNode> nodes) {
        return nodes.stream().map(CategoryProcessor::toInfo).toList();
    }

    private static CategoryNodeInfo toInfo(CategoryNode node) {
        Category category = node.category();
        return new CategoryNodeInfo(category.id(), category.name(), category.displayOrder(), toInfos(node.children()));
    }
}
