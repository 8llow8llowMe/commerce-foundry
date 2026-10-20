package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.category.domain.model.Category;
import com.commercefoundry.domainlayer.category.domain.model.CategoryNode;
import com.commercefoundry.domainlayer.category.domain.model.CategoryPlacementViolation;
import com.commercefoundry.domainlayer.category.domain.model.CategoryTree;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.CategoryAdminInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCategoryRepositoryPort;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 어드민 카테고리 쓰기 판정 — 트리 판정(깊이 · 순환 · 상위 존재 · 하위 존재)은 카테고리 domain({@link CategoryTree})이 하고, 여기서는 그 결과에
 * 업무 오류 코드를 붙인다. "연결된 상품" 은 상품 컨텍스트만 알아 이 Processor 가 직접 본다.
 *
 * <p>트리는 {@code CategoryAdminFacade} 가 카테고리 컨텍스트 {@code CategoryProcessor#getTreeForUpdate()} 에서 받아 넘긴다 — 다른 컨텍스트 Processor 가
 * 돌려준 domain model 을 읽기 전용으로 받는 것은 허용된 의존이다 ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙). 쓰기는 하지 않는다.
 * 응답용으로는 그 domain model 을 {@link CategoryAdminInfo} 로 옮긴다 — 다른 컨텍스트 domain model 은 Presenter 가 쓰지 않는다(H8).
 */
@Component
@RequiredArgsConstructor
public class CategoryAdminProcessor {

    private final ProductCategoryRepositoryPort productCategoryRepositoryPort;

    /** 새 카테고리를 {@code parentId} 아래(null 이면 최상위)에 둘 수 있는가. */
    public void validateCreate(CategoryTree tree, Long parentId) {
        validatePlacement(tree, null, parentId);
    }

    /**
     * 수정 대상이 있는가, 상위를 바꾸면 그 아래에 둘 수 있는가. 상위를 그대로 두는 수정(이름 · 순서 · 노출)은 깊이 · 순환을 다시 보지 않는다 —
     * 계층이 바뀌지 않는다.
     */
    public void validateUpdate(CategoryTree tree, long categoryId, Long parentId) {
        Category current = getExisting(tree, categoryId);
        if (!Objects.equals(current.parentId(), parentId)) {
            validatePlacement(tree, categoryId, parentId);
        }
    }

    /**
     * 지울 수 있는가 — 없으면 404, 하위가 있으면 409 {@code PRODUCT_042}, 상품의 대표 · 진열 카테고리면 409 {@code PRODUCT_043}.
     * 하위 판정이 메모리라 먼저 하고, 질의가 필요한 연결 상품 판정을 뒤에 한다.
     */
    public void validateDelete(CategoryTree tree, long categoryId) {
        getExisting(tree, categoryId);
        if (tree.hasChildren(categoryId)) {
            throw new ProductException(ProductErrorCode.CATEGORY_HAS_CHILDREN);
        }
        if (productCategoryRepositoryPort.existsProductInCategory(categoryId)) {
            throw new ProductException(ProductErrorCode.CATEGORY_HAS_PRODUCTS);
        }
    }

    /** 어드민 트리 — 카테고리 domain 노드를 상품 컨텍스트 Info 로 옮긴다. 순서는 그대로다. */
    public List<CategoryAdminInfo> toInfos(List<CategoryNode> nodes) {
        return nodes.stream().map(node -> toInfo(node.category(), toInfos(node.children()))).toList();
    }

    /** 생성 · 수정 결과 — 하위 없이 자기 자신만. */
    public CategoryAdminInfo toInfo(Category category) {
        return toInfo(category, List.of());
    }

    private static CategoryAdminInfo toInfo(Category category, List<CategoryAdminInfo> children) {
        return new CategoryAdminInfo(category.id(), category.parentId(), category.name(), category.displayOrder(), category.visible(), children);
    }

    private static Category getExisting(CategoryTree tree, long categoryId) {
        return tree.find(categoryId).orElseThrow(() -> new ProductException(ProductErrorCode.NOT_FOUND_CATEGORY));
    }

    private static void validatePlacement(CategoryTree tree, Long categoryId, Long parentId) {
        tree.placementViolationOf(categoryId, parentId).ifPresent(violation -> {
            throw new ProductException(errorCodeOf(violation));
        });
    }

    private static ProductErrorCode errorCodeOf(CategoryPlacementViolation violation) {
        return switch (violation) {
            case PARENT_NOT_FOUND -> ProductErrorCode.CATEGORY_PARENT_NOT_FOUND;
            case DEPTH_EXCEEDED -> ProductErrorCode.CATEGORY_DEPTH_EXCEEDED;
            case CYCLE -> ProductErrorCode.CATEGORY_CYCLE;
        };
    }
}
