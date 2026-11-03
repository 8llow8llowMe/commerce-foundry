package com.commercefoundry.domainlayer.product.application.service;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.category.application.service.processor.CategoryProcessor;
import com.commercefoundry.domainlayer.category.domain.model.CategoryTree;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductListAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductCreateAdminResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.presenter.ProductAdminPresenter;
import com.commercefoundry.domainlayer.product.application.command.ProductAdminSearchCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductCreateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.ProductAdminDetailInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductAdminListItemInfo;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSortType;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductAdminDetailProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductAdminQueryProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductDescriptionSanitizer;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductWriteProcessor;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * 어드민 상품 Facade — 목록: 카테고리를 하위까지 펼쳐 넘기는지, Presenter 가 페이지 정보 · 아이디 String · 금액 · enum 메타데이터로 바꾸는지 본다.
 * 등록(#9 E1): 판정 → 카테고리 잠금 → 저장 순서, 잠금 실패의 409, 정제 여부 응답.
 * Presenter 는 실제 객체를 쓴다.
 */
@ExtendWith(MockitoExtension.class)
class ProductAdminFacadeTest {

    private static final ProductAdminSearchCommand ALL = ProductAdminSearchCommand.builder().sortType(ProductAdminSortType.NEWEST).page(0).size(20).build();

    @Mock
    private CategoryProcessor categoryProcessor;

    @Mock
    private ProductAdminQueryProcessor productAdminQueryProcessor;

    @Mock
    private ProductAdminDetailProcessor productAdminDetailProcessor;

    @Mock
    private ProductWriteProcessor productWriteProcessor;

    @Mock
    private com.commercefoundry.domainlayer.product.application.service.processor.ProductDeleteProcessor productDeleteProcessor;

    @Mock
    private com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor productAdminAuditProcessor;

    @Mock
    private com.commercefoundry.domainlayer.product.application.service.processor.ProductCategoryUpdateProcessor productCategoryUpdateProcessor;

    private ProductAdminFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ProductAdminFacade(idGenerator(), new com.commercefoundry.domainlayer.product.application.service.processor.ProductRegistrationProcessor(
            productWriteProcessor, productAdminDetailProcessor,
            org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort.class)),
            attachments(), new TestTransactionManager(null), categoryProcessor, productAdminQueryProcessor, new ProductAdminPresenter(), productAdminDetailProcessor,
            productWriteProcessor, new ProductDescriptionSanitizer(), org.mockito.Mockito.mock(
            com.commercefoundry.domainlayer.product.application.service.processor.ProductImageUploadProcessor.class),
            productDeleteProcessor, org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.service.processor.ProductHeaderUpdateProcessor.class),
            productCategoryUpdateProcessor,
            productAdminAuditProcessor, org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.service.processor.ProductPricingUpdateProcessor.class),
            org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.service.processor.ProductImagesPrepareProcessor.class),
            org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.service.processor.ProductImagesUpdateProcessor.class),
            org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.service.processor.ProductOptionsUpdateProcessor.class));
    }

    private com.commercefoundry.persistence.util.SnowflakeIdGenerator idGenerator() {
        var ids = org.mockito.Mockito.mock(com.commercefoundry.persistence.util.SnowflakeIdGenerator.class);
        org.mockito.Mockito.lenient().when(ids.generateId()).thenReturn(7L);
        return ids;
    }

    private com.commercefoundry.domainlayer.product.application.service.processor.ProductImageAttachmentProcessor attachments() {
        return new com.commercefoundry.domainlayer.product.application.service.processor.ProductImageAttachmentProcessor(
            org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.port.out.ProductImageStoragePort.class), idGenerator());
    }

    @Test
    void categoryUpdateLocksTreeBeforeLoadingAndTranslatesBusyLock() {
        var command = new com.commercefoundry.domainlayer.product.application.command.ProductCategoryUpdateCommand(7L, 0, 99L, 1L, List.of());
        var tree = CategoryTree.of(List.of());
        given(categoryProcessor.getTreeForUpdate()).willReturn(tree);
        given(productCategoryUpdateProcessor.update(command, tree)).willThrow(new ProductException(ProductErrorCode.NOT_FOUND_PRODUCT));
        assertThatThrownBy(() -> facade.updateCategories(command)).isInstanceOf(ProductException.class);
        var order = inOrder(categoryProcessor, productCategoryUpdateProcessor);
        order.verify(categoryProcessor).getTreeForUpdate();
        order.verify(productCategoryUpdateProcessor).update(command, tree);
        verify(productAdminAuditProcessor).productCategoriesReplaced(99L, 7L, false);

        given(categoryProcessor.getTreeForUpdate()).willThrow(new PessimisticLockingFailureException("busy"));
        assertThatThrownBy(() -> facade.updateCategories(command)).isInstanceOfSatisfying(ProductException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.CATEGORY_BUSY));
    }

    @Test
    void deleteDelegatesWithoutQueryingChildrenOrIssuingUploads() {
        facade.delete(7L, 99L);
        verify(productDeleteProcessor).delete(7L);
        verify(productAdminAuditProcessor).productDeleted(99L, 7L, true);
        verifyNoInteractions(categoryProcessor, productAdminDetailProcessor, productAdminQueryProcessor, productWriteProcessor);
    }

    @Test
    void failedDeleteKeepsOriginalFailureAndAuditTarget() {
        var failure = new com.commercefoundry.domainlayer.product.application.exception.ProductException(
            com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode.NOT_FOUND_PRODUCT);
        org.mockito.Mockito.doThrow(failure).when(productDeleteProcessor).delete(7L);
        assertThatThrownBy(() -> facade.delete(7L, 99L)).isSameAs(failure);
        verify(productAdminAuditProcessor).productDeleted(99L, 7L, false);
    }

    @Test
    @DisplayName("등록 — 구성 판정을 잠금 전에 하고, 잠근 카테고리 트리로 존재를 본 뒤 정제본을 저장한다. 응답은 저장된 상세와 정제 여부다")
    void create() {
        ProductCreateCommand command = createCommand("<p>본문</p><script>alert(1)</script>");
        CategoryTree tree = CategoryTree.of(List.of());
        given(productWriteProcessor.displayCategoryIdsOf(command)).willReturn(List.of(12L));
        given(categoryProcessor.getTreeForUpdate()).willReturn(tree);
        given(productWriteProcessor.create(command, "<p>본문</p>", List.of(12L), 7L)).willReturn(7L);
        given(productAdminDetailProcessor.getProduct(7L)).willReturn(detailInfo(7L));

        ProductCreateAdminResponse response = facade.create(command);

        InOrder order = inOrder(productWriteProcessor, categoryProcessor);
        order.verify(productWriteProcessor).validate(command);
        order.verify(categoryProcessor).getTreeForUpdate();
        order.verify(productWriteProcessor).requireCategories(tree, List.of(12L));
        order.verify(productWriteProcessor).create(command, "<p>본문</p>", List.of(12L), 7L);
        assertThat(response.descriptionHtmlSanitized()).isTrue();
        assertThat(response.product().productId()).isEqualTo("7");
    }

    @Test
    @DisplayName("등록 — 카테고리 잠금을 시간 안에 얻지 못하면 409 · PRODUCT_045 이고 저장하지 않는다")
    void createLockTimeout() {
        ProductCreateCommand command = createCommand(null);
        given(productWriteProcessor.displayCategoryIdsOf(command)).willReturn(List.of(12L));
        given(categoryProcessor.getTreeForUpdate()).willThrow(new PessimisticLockingFailureException("lock"));

        assertThatThrownBy(() -> facade.create(command))
            .isInstanceOfSatisfying(ProductException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(ProductErrorCode.CATEGORY_BUSY));
        verify(productWriteProcessor, never()).create(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    private static ProductCreateCommand createCommand(String descriptionHtml) {
        return ProductCreateCommand.builder()
            .name("저고리").descriptionHtml(descriptionHtml).categoryId(12L).categoryIds(List.of())
            .saleStatus(SaleStatus.ON_SALE).visible(true).salePrice(Money.of(59_000, KRW))
            .optionGroups(List.of())
            .skus(List.of(ProductCreateCommand.Sku.builder().optionValues(List.of()).additionalPrice(Money.of(0, KRW)).saleStatus(SaleStatus.ON_SALE).build()))
            .createdByMemberId(100L)
            .build();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void deletesOnlyWhenTransactionConfirmsRollback(boolean confirmedRollback) {
        var attachment = org.mockito.Mockito.mock(
            com.commercefoundry.domainlayer.product.application.service.processor.ProductImageAttachmentProcessor.class);
        var registration = org.mockito.Mockito.mock(
            com.commercefoundry.domainlayer.product.application.service.processor.ProductRegistrationProcessor.class);
        var command = createCommand(null);
        var image = new com.commercefoundry.domainlayer.product.domain.model.ProductImage(8, 7,
            com.commercefoundry.domainlayer.product.domain.model.ImageType.THUMBNAIL, "https://media.example.com/8.png",
            null, null, null, 0, null, "products/7/8.png");
        given(attachment.attach(7, command)).willReturn(List.of(image));
        RuntimeException failure = confirmedRollback ? new IllegalStateException("registration failed")
            : new org.springframework.transaction.TransactionSystemException("commit outcome unknown");
        org.mockito.Mockito.doAnswer(call -> {
            if (confirmedRollback) {
                throw failure;
            }
            return detailInfo(7L);
        }).when(registration).register(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(command),
            isNull(), any(), any(), any());
        var subject = new ProductAdminFacade(idGenerator(), registration, attachment,
            new TestTransactionManager(confirmedRollback ? null : failure), categoryProcessor, productAdminQueryProcessor,
            new ProductAdminPresenter(), productAdminDetailProcessor, productWriteProcessor, new ProductDescriptionSanitizer(), null,
            productDeleteProcessor, org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.service.processor.ProductHeaderUpdateProcessor.class),
            productCategoryUpdateProcessor,
            productAdminAuditProcessor, org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.service.processor.ProductPricingUpdateProcessor.class),
            org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.service.processor.ProductImagesPrepareProcessor.class),
            org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.service.processor.ProductImagesUpdateProcessor.class),
            org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.service.processor.ProductOptionsUpdateProcessor.class));
        assertThatThrownBy(() -> subject.create(command)).isSameAs(failure);
        verify(attachment, org.mockito.Mockito.times(confirmedRollback ? 1 : 0)).cleanup(List.of("products/7/8.png"));
    }

    @Test
    void committedImageSurvivesPresenterFailure() {
        var attachment = org.mockito.Mockito.mock(
            com.commercefoundry.domainlayer.product.application.service.processor.ProductImageAttachmentProcessor.class);
        var registration = org.mockito.Mockito.mock(
            com.commercefoundry.domainlayer.product.application.service.processor.ProductRegistrationProcessor.class);
        var presenter = org.mockito.Mockito.mock(ProductAdminPresenter.class);
        var command = createCommand(null);
        given(attachment.attach(7, command)).willReturn(List.of(new com.commercefoundry.domainlayer.product.domain.model.ProductImage(
            8, 7, com.commercefoundry.domainlayer.product.domain.model.ImageType.THUMBNAIL,
            "https://media.example.com/8.png", null, null, null, 0, null, "products/7/8.png")));
        given(registration.register(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(command),
            isNull(), any(), any(), any())).willReturn(detailInfo(7L));
        var failure = new IllegalStateException("response conversion");
        given(presenter.toCreateResponse(any(), org.mockito.ArgumentMatchers.anyBoolean())).willThrow(failure);
        var subject = new ProductAdminFacade(idGenerator(), registration, attachment, new TestTransactionManager(null),
            categoryProcessor, productAdminQueryProcessor,
            presenter, productAdminDetailProcessor, productWriteProcessor, new ProductDescriptionSanitizer(), null,
            productDeleteProcessor, org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.service.processor.ProductHeaderUpdateProcessor.class),
            productCategoryUpdateProcessor,
            productAdminAuditProcessor, org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.service.processor.ProductPricingUpdateProcessor.class),
            org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.service.processor.ProductImagesPrepareProcessor.class),
            org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.service.processor.ProductImagesUpdateProcessor.class),
            org.mockito.Mockito.mock(com.commercefoundry.domainlayer.product.application.service.processor.ProductOptionsUpdateProcessor.class));
        assertThatThrownBy(() -> subject.create(command)).isSameAs(failure);
        verify(attachment, never()).cleanup(any());
    }

    /** 실제 Spring 완료 콜백을 실행하되 DB 자원은 필요 없는 단위 테스트용 트랜잭션 매니저. */
    private static final class TestTransactionManager extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        private final RuntimeException commitFailure;

        private TestTransactionManager(RuntimeException commitFailure) {
            this.commitFailure = commitFailure;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
            assertThat(definition.getTimeout()).isEqualTo(5);
        }

        @Override
        protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
            if (commitFailure != null) {
                throw commitFailure;
            }
        }

        @Override
        protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {
        }
    }

    private static ProductAdminDetailInfo detailInfo(long productId) {
        Product product = new Product(productId, 12L, "저고리", null, "<p>본문</p>", SaleStatus.ON_SALE, true, Money.of(59_000, KRW), null, null,
            false, null, null, false, null, 0L, null, 100L);
        return new ProductAdminDetailInfo(product, List.of(12L), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("카테고리 조건은 카테고리 컨텍스트가 펼친 하위 포함 집합으로 넘긴다")
    void expandsCategorySubtree() {
        ProductAdminSearchCommand command = ProductAdminSearchCommand.builder().categoryId(10L).sortType(ProductAdminSortType.NEWEST).page(0).size(20).build();
        given(categoryProcessor.subtreeIdsOf(10L)).willReturn(Set.of(10L, 11L, 111L));
        given(productAdminQueryProcessor.search(any(), any())).willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        facade.searchProducts(command);

        verify(productAdminQueryProcessor).search(command, Set.of(10L, 11L, 111L));
    }

    @Test
    @DisplayName("카테고리 조건이 없으면 카테고리를 읽지 않고 null 로 넘긴다")
    void noCategory() {
        given(productAdminQueryProcessor.search(any(), isNull())).willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        facade.searchProducts(ALL);

        verify(productAdminQueryProcessor).search(ALL, null);
        verifyNoInteractions(categoryProcessor);
    }

    @Test
    @DisplayName("응답은 페이지 정보(자르기 전 전체 건수)를 그대로 두고 항목을 아이디 String · {amount, currency} · {code, name, description} 으로 바꾼다")
    void presentsPage() {
        ProductAdminListItemInfo info = ProductAdminListItemInfo.builder()
            .productId(4611686018427387904L)
            .name("저고리")
            .thumbnailUrl("https://cdn.example.com/p.jpg")
            .salePrice(Money.of(39_000, KRW))
            .listPrice(null)
            .saleStatus(SaleStatus.STOP_SELLING)
            .visible(false)
            .availability(SaleAvailability.STOP_SELLING)
            .skuCount(2)
            .updatedAt(Instant.parse("2026-10-18T09:00:00Z"))
            .build();
        given(productAdminQueryProcessor.search(any(), isNull())).willReturn(new PageImpl<>(List.of(info), PageRequest.of(1, 1), 3));

        PageResponse<ProductListAdminItem> page = facade.searchProducts(ALL);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(3);
        ProductListAdminItem item = page.contents().getFirst();
        assertThat(item.productId()).isEqualTo("4611686018427387904");
        assertThat(item.thumbnailUrl()).isEqualTo("https://cdn.example.com/p.jpg");
        assertThat(item.salePrice().amount()).isEqualTo(39_000);
        assertThat(item.salePrice().currency()).isEqualTo("KRW");
        assertThat(item.listPrice()).isNull();
        assertThat(item.saleStatus().code()).isEqualTo("STOP_SELLING");
        assertThat(item.saleStatus().name()).isEqualTo("판매 종료");
        assertThat(item.availability().code()).isEqualTo("STOP_SELLING");
        assertThat(item.visible()).isFalse();
        assertThat(item.skuCount()).isEqualTo(2);
        assertThat(item.updatedAt()).isEqualTo(Instant.parse("2026-10-18T09:00:00Z"));
    }
}
