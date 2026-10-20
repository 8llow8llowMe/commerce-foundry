package com.commercefoundry.domainlayer.product.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.category.domain.model.Category;
import com.commercefoundry.domainlayer.category.domain.model.CategoryTree;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.CategoryAdminInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCategoryRepositoryPort;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoryAdminProcessorTest {

    //  상의(1) ─ 저고리(11) ─ 반팔(111)
    //  하의(2, 숨김)
    private static final CategoryTree TREE = CategoryTree.of(List.of(
        new Category(1, null, "상의", 0, true),
        new Category(2, null, "하의", 1, false),
        new Category(11, 1L, "저고리", 0, true),
        new Category(111, 11L, "반팔", 0, true)
    ));

    @Mock
    private ProductCategoryRepositoryPort productCategoryRepositoryPort;

    @InjectMocks
    private CategoryAdminProcessor categoryAdminProcessor;

    @Nested
    @DisplayName("validateCreate")
    class ValidateCreate {

        @Test
        @DisplayName("최상위 · 2단계 아래(3단계가 된다)는 통과한다")
        void allowed() {
            assertThatCode(() -> categoryAdminProcessor.validateCreate(TREE, null)).doesNotThrowAnyException();
            assertThatCode(() -> categoryAdminProcessor.validateCreate(TREE, 11L)).doesNotThrowAnyException();
            assertThatCode(() -> categoryAdminProcessor.validateCreate(TREE, 2L)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("없는 상위면 400 · PRODUCT_044")
        void parentNotFound() {
            assertErrorCode(() -> categoryAdminProcessor.validateCreate(TREE, 999L), ProductErrorCode.CATEGORY_PARENT_NOT_FOUND);
        }

        @Test
        @DisplayName("3단계 아래면 400 · PRODUCT_040")
        void depthExceeded() {
            assertErrorCode(() -> categoryAdminProcessor.validateCreate(TREE, 111L), ProductErrorCode.CATEGORY_DEPTH_EXCEEDED);
        }
    }

    @Nested
    @DisplayName("validateUpdate")
    class ValidateUpdate {

        @Test
        @DisplayName("없는 카테고리면 404 · PRODUCT_004")
        void notFound() {
            assertErrorCode(() -> categoryAdminProcessor.validateUpdate(TREE, 999L, null), ProductErrorCode.NOT_FOUND_CATEGORY);
        }

        @Test
        @DisplayName("상위를 바꾸지 않으면 깊이 · 순환을 다시 보지 않는다")
        void sameParent() {
            assertThatCode(() -> categoryAdminProcessor.validateUpdate(TREE, 111L, 11L)).doesNotThrowAnyException();
            assertThatCode(() -> categoryAdminProcessor.validateUpdate(TREE, 1L, null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("다른 상위로 옮길 수 있으면 통과한다")
        void move() {
            assertThatCode(() -> categoryAdminProcessor.validateUpdate(TREE, 11L, 2L)).doesNotThrowAnyException();
            assertThatCode(() -> categoryAdminProcessor.validateUpdate(TREE, 11L, null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("자기 하위를 상위로 지정하면 400 · PRODUCT_041")
        void cycle() {
            assertErrorCode(() -> categoryAdminProcessor.validateUpdate(TREE, 1L, 111L), ProductErrorCode.CATEGORY_CYCLE);
            assertErrorCode(() -> categoryAdminProcessor.validateUpdate(TREE, 11L, 11L), ProductErrorCode.CATEGORY_CYCLE);
        }

        @Test
        @DisplayName("하위까지 3단계를 넘으면 400 · PRODUCT_040")
        void depthExceeded() {
            assertErrorCode(() -> categoryAdminProcessor.validateUpdate(TREE, 1L, 2L), ProductErrorCode.CATEGORY_DEPTH_EXCEEDED);
        }

        @Test
        @DisplayName("없는 상위로 옮기면 400 · PRODUCT_044")
        void parentNotFound() {
            assertErrorCode(() -> categoryAdminProcessor.validateUpdate(TREE, 11L, 999L), ProductErrorCode.CATEGORY_PARENT_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("validateDelete")
    class ValidateDelete {

        @Test
        @DisplayName("하위도 연결 상품도 없으면 통과한다")
        void deletable() {
            given(productCategoryRepositoryPort.existsProductInCategory(111L)).willReturn(false);

            assertThatCode(() -> categoryAdminProcessor.validateDelete(TREE, 111L)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("없는 카테고리면 404 · PRODUCT_004 이고 상품 질의를 하지 않는다")
        void notFound() {
            assertErrorCode(() -> categoryAdminProcessor.validateDelete(TREE, 999L), ProductErrorCode.NOT_FOUND_CATEGORY);
            verifyNoInteractions(productCategoryRepositoryPort);
        }

        @Test
        @DisplayName("하위가 있으면 409 · PRODUCT_042 이고 상품 질의를 하지 않는다")
        void hasChildren() {
            assertErrorCode(() -> categoryAdminProcessor.validateDelete(TREE, 11L), ProductErrorCode.CATEGORY_HAS_CHILDREN);
            verifyNoInteractions(productCategoryRepositoryPort);
        }

        @Test
        @DisplayName("상품의 대표 · 진열 카테고리면 409 · PRODUCT_043")
        void hasProducts() {
            given(productCategoryRepositoryPort.existsProductInCategory(2L)).willReturn(true);

            assertErrorCode(() -> categoryAdminProcessor.validateDelete(TREE, 2L), ProductErrorCode.CATEGORY_HAS_PRODUCTS);
            verify(productCategoryRepositoryPort).existsProductInCategory(2L);
        }
    }

    @Test
    @DisplayName("toInfos 는 숨김을 포함한 트리를 순서 그대로 Info 로 옮기고, toInfo 는 하위 없이 자기 자신만 옮긴다")
    void toInfos() {
        List<CategoryAdminInfo> infos = categoryAdminProcessor.toInfos(TREE.allRoots());

        assertThat(infos).containsExactly(
            new CategoryAdminInfo(1, null, "상의", 0, true, List.of(
                new CategoryAdminInfo(11, 1L, "저고리", 0, true, List.of(new CategoryAdminInfo(111, 11L, "반팔", 0, true, List.of())))
            )),
            new CategoryAdminInfo(2, null, "하의", 1, false, List.of())
        );
        assertThat(categoryAdminProcessor.toInfo(new Category(11, 1L, "저고리", 0, true)))
            .isEqualTo(new CategoryAdminInfo(11, 1L, "저고리", 0, true, List.of()));
    }

    private static void assertErrorCode(ThrowingCallable call, ProductErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProductException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
