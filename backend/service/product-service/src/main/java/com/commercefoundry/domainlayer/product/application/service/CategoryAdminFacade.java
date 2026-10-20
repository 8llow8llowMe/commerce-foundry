package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.category.application.service.processor.CategoryProcessor;
import com.commercefoundry.domainlayer.category.domain.model.Category;
import com.commercefoundry.domainlayer.category.domain.model.CategoryTree;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.CategoryAdminResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.CategoryAdminTreeResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.presenter.CategoryAdminPresenter;
import com.commercefoundry.domainlayer.product.application.command.CategoryUpsertCommand;
import com.commercefoundry.domainlayer.product.application.port.in.CategoryAdminUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.CategoryAdminProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 카테고리 관리. 카테고리 읽기 · 쓰기는 카테고리 컨텍스트 {@link CategoryProcessor} 가, 쓰기 판정(오류 코드 · 연결 상품)은 상품 컨텍스트
 * {@link CategoryAdminProcessor} 가 한다 — 다른 컨텍스트에서는 Processor 와 그 domain model 만 쓴다 ({@code architecture-guide.md} §3).
 *
 * <p>쓰기는 외부 I/O 가 없어 Facade 에 트랜잭션을 건다. 먼저 {@link CategoryProcessor#getTreeForUpdate()} 로 모든 카테고리 행을 잠가 어드민 쓰기를
 * 직렬화한다 — 판정한 트리가 저장할 때까지 바뀌지 않아, 동시에 두 카테고리를 서로의 아래로 옮기거나 지우는 사이 하위가 생기는 경우를 막는다.
 */
@Service
@RequiredArgsConstructor
public class CategoryAdminFacade implements CategoryAdminUseCase {

    private final CategoryProcessor categoryProcessor;
    private final CategoryAdminProcessor categoryAdminProcessor;
    private final CategoryAdminPresenter categoryAdminPresenter;

    @Override
    @Transactional(readOnly = true)
    public CategoryAdminTreeResponse getCategoryTree() {
        return categoryAdminPresenter.toTreeResponse(categoryAdminProcessor.toInfos(categoryProcessor.getAdminTree()));
    }

    @Override
    @Transactional
    public CategoryAdminResponse createCategory(CategoryUpsertCommand command) {
        CategoryTree tree = categoryProcessor.getTreeForUpdate();
        categoryAdminProcessor.validateCreate(tree, command.parentId());
        Category created = categoryProcessor.create(command.parentId(), command.name(), command.displayOrder(), command.visible());
        return categoryAdminPresenter.toResponse(categoryAdminProcessor.toInfo(created));
    }

    @Override
    @Transactional
    public CategoryAdminResponse updateCategory(long categoryId, CategoryUpsertCommand command) {
        CategoryTree tree = categoryProcessor.getTreeForUpdate();
        categoryAdminProcessor.validateUpdate(tree, categoryId, command.parentId());
        Category updated = categoryProcessor.update(categoryId, command.parentId(), command.name(), command.displayOrder(), command.visible());
        return categoryAdminPresenter.toResponse(categoryAdminProcessor.toInfo(updated));
    }

    @Override
    @Transactional
    public void deleteCategory(long categoryId) {
        CategoryTree tree = categoryProcessor.getTreeForUpdate();
        categoryAdminProcessor.validateDelete(tree, categoryId);
        categoryProcessor.delete(categoryId);
    }
}
