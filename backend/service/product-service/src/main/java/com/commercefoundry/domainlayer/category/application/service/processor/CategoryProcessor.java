package com.commercefoundry.domainlayer.category.application.service.processor;

import com.commercefoundry.domainlayer.category.application.info.CategoryNodeInfo;
import com.commercefoundry.domainlayer.category.application.info.CategoryTreeInfo;
import com.commercefoundry.domainlayer.category.application.port.out.CategoryRepositoryPort;
import com.commercefoundry.domainlayer.category.domain.model.Category;
import com.commercefoundry.domainlayer.category.domain.model.CategoryNode;
import com.commercefoundry.domainlayer.category.domain.model.CategoryTree;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카테고리 읽기 · 쓰기. 카테고리는 수가 적어 전체를 한 번 읽고 {@link CategoryTree} 로 메모리에서 탐색한다.
 *
 * <p>같은 서비스의 다른 컨텍스트(상품)는 용도별 메서드({@link #subtreeIdsOf} · {@link #visiblePathOf})만 부른다 — 숨김 같은 조회 규칙은
 * 이 컨텍스트가 정한다. {@link CategoryRepositoryPort} 를 직접 주입하지 않는다 ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙).
 *
 * <p>어드민 카테고리 관리는 상품 컨텍스트의 {@code CategoryAdminFacade} 가 부른다 — 삭제 판정에 "연결된 상품" 이 필요해서다(의존 방향 product → category).
 * 쓰기 메서드는 판정을 하지 않는다. Facade 가 {@link #getTreeForUpdate()} 로 잠근 트리를 받아 상품 컨텍스트 Processor 에 판정을 맡긴 뒤 부른다.
 */
@Component
@RequiredArgsConstructor
public class CategoryProcessor {

    private final CategoryRepositoryPort categoryRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

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
     * 상품 상세 breadcrumb — 메뉴에서 닿을 수 있는 경로만 낸다. 최상위부터 따라가다 처음 숨긴 카테고리에서 멈춘다({@link CategoryTree#visiblePathTo}, #164).
     *
     * @return 없는 아이디(대표 카테고리 데이터 오류) · 최상위가 숨김이면 빈 목록
     */
    public List<Category> visiblePathOf(long categoryId) {
        return loadTree().visiblePathTo(categoryId);
    }

    /** 어드민 트리 — 숨긴 카테고리와 그 하위도 담는다 ({@link CategoryTree#allRoots()}). */
    public List<CategoryNode> getAdminTree() {
        return loadTree().allRoots();
    }

    /**
     * 어드민 쓰기 판정용 트리 — 모든 카테고리 행을 잠근 뒤 읽는다. 호출한 Facade 의 트랜잭션이 끝날 때까지 다른 어드민 쓰기가 기다리므로,
     * 이 트리로 한 깊이 · 순환 · 하위 판정이 저장할 때까지 유효하다.
     *
     * <p>{@code MANDATORY} — 트랜잭션 밖에서 부르면 바로 실패한다. 잠금은 문장이 끝나면 풀려 판정과 쓰기 사이를 지키지 못하기 때문이다.
     * 아래 쓰기 메서드도 같은 이유로 {@code MANDATORY} 다 — 잠금 없는 쓰기를 막는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CategoryTree getTreeForUpdate() {
        return CategoryTree.of(categoryRepositoryPort.findAllForUpdate());
    }

    /** 새 카테고리 — 아이디를 발급해 저장한다. 깊이 · 상위 판정은 호출한 쪽이 {@link #getTreeForUpdate()} 트리로 끝냈다. 트랜잭션 밖이면 실패한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Category create(Long parentId, String name, int displayOrder, boolean visible) {
        Category category = new Category(snowflakeIdGenerator.generateId(), parentId, name, displayOrder, visible);
        categoryRepositoryPort.insert(category);
        return category;
    }

    /** 이미 있는 카테고리를 통째로 바꾼다(상위 이동 포함). 존재 · 깊이 · 순환 판정은 호출한 쪽이 끝냈다. 트랜잭션 밖이면 실패한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Category update(long categoryId, Long parentId, String name, int displayOrder, boolean visible) {
        Category category = new Category(categoryId, parentId, name, displayOrder, visible);
        categoryRepositoryPort.update(category);
        return category;
    }

    /** 하위 · 연결 상품 판정은 호출한 쪽이 끝냈다. 트랜잭션 밖이면 실패한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(long categoryId) {
        categoryRepositoryPort.deleteById(categoryId);
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
