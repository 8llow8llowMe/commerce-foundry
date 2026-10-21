package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.category.application.service.processor.CategoryProcessor;
import com.commercefoundry.domainlayer.category.domain.model.Category;
import com.commercefoundry.domainlayer.category.domain.model.CategoryTree;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.CategoryAdminResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.CategoryAdminTreeResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.presenter.CategoryAdminPresenter;
import com.commercefoundry.domainlayer.product.application.command.CategoryUpsertCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.CategoryAdminUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.CategoryAdminProcessor;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 카테고리 관리. 카테고리 읽기 · 쓰기는 카테고리 컨텍스트 {@link CategoryProcessor} 가, 쓰기 판정(오류 코드 · 연결 상품)은 상품 컨텍스트
 * {@link CategoryAdminProcessor} 가 한다 — 다른 컨텍스트에서는 Processor 와 그 domain model 만 쓴다 ({@code architecture-guide.md} §3).
 *
 * <p>쓰기는 외부 I/O 가 없어 Facade 에 트랜잭션을 건다. 먼저 {@link CategoryProcessor#getTreeForUpdate()} 로 모든 카테고리 행을 잠가 어드민 쓰기를
 * 직렬화한다 — 판정한 트리가 저장할 때까지 바뀌지 않아, 동시에 두 카테고리를 서로의 아래로 옮기거나 지우는 사이 하위가 생기는 경우를 막는다.
 *
 * <p><b>잠금 대기 상한</b> — 쓰기 메서드는 트랜잭션 시간 상한({@value #LOCKING_TIMEOUT_SECONDS}초)을 건다. JPA 트랜잭션 관리자가 남은 시간을
 * 쿼리 타임아웃으로 넣어 잠금을 기다리는 {@code select ... for update} 도 그 안에 끊긴다(드라이버가 문장을 취소 — Spring {@link QueryTimeoutException}).
 * 상한이 이미 지난 뒤의 쿼리는 {@link TransactionTimedOutException} 이다. 둘 다 다른 어드민의 카테고리 쓰기와 겹친 것이라 409 · {@code PRODUCT_045} 로 바꾼다
 * (선례: auth {@code ShippingAddressWebFacade}).
 */
@Service
@RequiredArgsConstructor
public class CategoryAdminFacade implements CategoryAdminUseCase {

    /** 카테고리 잠금을 거는 트랜잭션의 시간 상한(초). 잠금은 짧은 쓰기 몇 문장만 쥐므로 정상 경로는 이 값에 한참 못 미친다. */
    static final int LOCKING_TIMEOUT_SECONDS = 5;

    private final CategoryProcessor categoryProcessor;
    private final CategoryAdminProcessor categoryAdminProcessor;
    private final CategoryAdminPresenter categoryAdminPresenter;

    @Override
    @Transactional(readOnly = true)
    public CategoryAdminTreeResponse getCategoryTree() {
        return categoryAdminPresenter.toTreeResponse(categoryAdminProcessor.toInfos(categoryProcessor.getAdminTree()));
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public CategoryAdminResponse createCategory(CategoryUpsertCommand command) {
        Category created = inCategoryQueue(tree -> {
            categoryAdminProcessor.validateCreate(tree, command.parentId());
            return categoryProcessor.create(command.parentId(), command.name(), command.displayOrder(), command.visible());
        });
        return categoryAdminPresenter.toResponse(categoryAdminProcessor.toInfo(created));
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public CategoryAdminResponse updateCategory(long categoryId, CategoryUpsertCommand command) {
        Category updated = inCategoryQueue(tree -> {
            categoryAdminProcessor.validateUpdate(tree, categoryId, command.parentId());
            return categoryProcessor.update(categoryId, command.parentId(), command.name(), command.displayOrder(), command.visible());
        });
        return categoryAdminPresenter.toResponse(categoryAdminProcessor.toInfo(updated));
    }

    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public void deleteCategory(long categoryId) {
        inCategoryQueue(tree -> {
            categoryAdminProcessor.validateDelete(tree, categoryId);
            categoryProcessor.delete(categoryId);
            return null;
        });
    }

    /**
     * 모든 카테고리 행을 잠근 트리로 판정 · 쓰기를 한다. 잠금 대기 · 작업 중 시간 상한을 넘기면 409 · {@code PRODUCT_045} 다 — 트랜잭션은 예외로 롤백된다.
     * {@link PessimisticLockingFailureException}(잠금 획득 실패 · 교착 상태 희생)도 같은 뜻이라 같이 바꾼다.
     */
    private <T> T inCategoryQueue(Function<CategoryTree, T> work) {
        try {
            return work.apply(categoryProcessor.getTreeForUpdate());
        } catch (QueryTimeoutException | TransactionTimedOutException | PessimisticLockingFailureException exception) {
            throw new ProductException(ProductErrorCode.CATEGORY_BUSY, exception);
        }
    }
}
