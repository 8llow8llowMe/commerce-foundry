package com.commercefoundry.domainlayer.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.category.application.service.processor.CategoryProcessor;
import com.commercefoundry.domainlayer.category.domain.model.Category;
import com.commercefoundry.domainlayer.category.domain.model.CategoryTree;
import com.commercefoundry.domainlayer.product.adapter.in.admin.presenter.CategoryAdminPresenter;
import com.commercefoundry.domainlayer.product.application.command.CategoryUpsertCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.service.processor.CategoryAdminProcessor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

/**
 * 어드민 카테고리 쓰기의 감사 호출 (#163) — 업무가 끝나면 성공, 업무 예외면 실패를 한 번 남기고 예외는 그대로 던진다.
 * 커밋 · 롤백 결과 반영은 {@code CategoryAdminAuditIntegrationTest} 가 실제 트랜잭션으로 본다.
 */
@ExtendWith(MockitoExtension.class)
class CategoryAdminFacadeTest {

    private static final long ACTOR = 77L;
    private static final CategoryUpsertCommand COMMAND = new CategoryUpsertCommand(null, "상의", 0, true);

    @Mock
    private CategoryProcessor categoryProcessor;

    @Mock
    private CategoryAdminProcessor categoryAdminProcessor;

    @Mock
    private CategoryAdminPresenter categoryAdminPresenter;

    @Mock
    private ProductAdminAuditProcessor productAdminAuditProcessor;

    @InjectMocks
    private CategoryAdminFacade facade;

    @BeforeEach
    void emptyTree() {
        lenient().when(categoryProcessor.getTreeForUpdate()).thenReturn(CategoryTree.of(List.of()));
    }

    @Test
    @DisplayName("생성 성공은 만든 아이디로 성공을 남긴다")
    void createSucceeded() {
        given(categoryProcessor.create(null, "상의", 0, true)).willReturn(new Category(12L, null, "상의", 0, true));

        facade.createCategory(COMMAND, ACTOR);

        verify(productAdminAuditProcessor).categoryCreated(ACTOR, 12L, true);
        verify(productAdminAuditProcessor, never()).categoryCreated(anyLong(), any(), eq(false));
    }

    @Test
    @DisplayName("생성 실패(없는 상위)는 아이디 없이 실패를 남기고 업무 예외를 그대로 던진다")
    void createFailed() {
        ProductException failure = new ProductException(ProductErrorCode.CATEGORY_PARENT_NOT_FOUND);
        willThrow(failure).given(categoryAdminProcessor).validateCreate(any(), any());

        assertThatThrownBy(() -> facade.createCategory(COMMAND, ACTOR)).isSameAs(failure);

        verify(productAdminAuditProcessor).categoryCreated(ACTOR, null, false);
        verify(productAdminAuditProcessor, never()).categoryCreated(anyLong(), any(), eq(true));
    }

    @Test
    @DisplayName("잠금 대기 초과는 409 · PRODUCT_045 로 바뀐 뒤 실패로 남는다")
    void createBusy() {
        given(categoryProcessor.getTreeForUpdate()).willThrow(new QueryTimeoutException("timeout"));

        assertThatThrownBy(() -> facade.createCategory(COMMAND, ACTOR))
            .isInstanceOfSatisfying(ProductException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.CATEGORY_BUSY));

        verify(productAdminAuditProcessor).categoryCreated(ACTOR, null, false);
    }

    @Test
    @DisplayName("수정은 경로 아이디로 성공 · 실패를 남긴다")
    void update() {
        given(categoryProcessor.update(101L, null, "상의", 0, true)).willReturn(new Category(101L, null, "상의", 0, true));

        facade.updateCategory(101L, COMMAND, ACTOR);

        verify(productAdminAuditProcessor).categoryUpdated(ACTOR, 101L, true);

        ProductException notFound = new ProductException(ProductErrorCode.NOT_FOUND_CATEGORY);
        willThrow(notFound).given(categoryAdminProcessor).validateUpdate(any(), eq(202L), any());

        assertThatThrownBy(() -> facade.updateCategory(202L, COMMAND, ACTOR)).isSameAs(notFound);

        verify(productAdminAuditProcessor).categoryUpdated(ACTOR, 202L, false);
        verify(categoryProcessor, never()).update(eq(202L), any(), any(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("삭제는 경로 아이디로 성공 · 실패(하위 있음 409)를 남긴다")
    void delete() {
        facade.deleteCategory(101L, ACTOR);

        verify(productAdminAuditProcessor).categoryDeleted(ACTOR, 101L, true);

        ProductException hasChildren = new ProductException(ProductErrorCode.CATEGORY_HAS_CHILDREN);
        willThrow(hasChildren).given(categoryAdminProcessor).validateDelete(any(), eq(202L));

        assertThatThrownBy(() -> facade.deleteCategory(202L, ACTOR)).isSameAs(hasChildren);

        verify(productAdminAuditProcessor).categoryDeleted(ACTOR, 202L, false);
        verify(categoryProcessor, never()).delete(202L);
    }
}
