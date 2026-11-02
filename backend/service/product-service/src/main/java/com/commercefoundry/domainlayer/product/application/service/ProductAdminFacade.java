package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.category.application.service.processor.CategoryProcessor;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductListAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductCreateAdminResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductDetailAdminResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductImageUploadAdminResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.presenter.ProductAdminPresenter;
import com.commercefoundry.domainlayer.product.application.command.ProductAdminSearchCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductCreateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductAdminDetailProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductDeleteProcessor;
import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductAdminQueryProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductDescriptionSanitizer;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductImageUploadProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductWriteProcessor;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 어드민 상품 관리. 카테고리는 카테고리 컨텍스트의 {@link CategoryProcessor} 용도별 메서드로만 받는다 ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙).
 * 조회는 외부 I/O 가 없어 Facade 에 읽기 전용 트랜잭션을 건다.
 *
 * <p><b>등록</b>(#9 F2)은 이미지 복사를 DB 트랜잭션 밖에서 끝내고 Facade 의 좁은 TransactionTemplate 에서만 카테고리 잠금과 등록을 묶는다.
 * 정제 · 구성 · 가격 판정을 잠금 전에 끝내고, {@link CategoryProcessor#getTreeForUpdate()} 로 카테고리 행을 잠가 존재를 본 뒤 저장한다 —
 * DB FK 가 없어 카테고리 삭제와 엇갈리면 지워진 카테고리를 가리키는 상품이 생기기 때문이다. 잠금을 시간 안에 얻지 못하면 409 · {@code PRODUCT_045} 다
 * (선례: {@code CategoryAdminFacade}).
 */
@Service
@RequiredArgsConstructor
public class ProductAdminFacade implements ProductAdminUseCase {

    /** 카테고리 잠금을 거는 등록 트랜잭션의 시간 상한(초) — {@code CategoryAdminFacade} 와 같다. */
    static final int LOCKING_TIMEOUT_SECONDS = 5;

    private final com.commercefoundry.persistence.util.SnowflakeIdGenerator snowflakeIdGenerator;
    private final com.commercefoundry.domainlayer.product.application.service.processor.ProductRegistrationProcessor productRegistrationProcessor;
    private final com.commercefoundry.domainlayer.product.application.service.processor.ProductImageAttachmentProcessor productImageAttachmentProcessor;
    private final PlatformTransactionManager transactionManager;
    private final CategoryProcessor categoryProcessor;
    private final ProductAdminQueryProcessor productAdminQueryProcessor;
    private final ProductAdminPresenter productAdminPresenter;
    private final ProductAdminDetailProcessor productAdminDetailProcessor;
    private final ProductWriteProcessor productWriteProcessor;
    private final ProductDescriptionSanitizer productDescriptionSanitizer;
    private final ProductImageUploadProcessor productImageUploadProcessor;
    private final ProductDeleteProcessor productDeleteProcessor;
    private final ProductAdminAuditProcessor productAdminAuditProcessor;

    /** Database-only deletion: read and versioned merge share one bounded transaction. */
    @Override
    @Transactional(timeout = LOCKING_TIMEOUT_SECONDS)
    public void delete(long productId, long actorMemberId) {
        try {
            productDeleteProcessor.delete(productId);
        } catch (RuntimeException exception) {
            productAdminAuditProcessor.productDeleted(actorMemberId, productId, false);
            throw exception;
        }
        productAdminAuditProcessor.productDeleted(actorMemberId, productId, true);
    }

    /** 로컬 서명이라 DB · 외부 호출이 없다 — 트랜잭션을 걸지 않는다. */
    @Override
    public ProductImageUploadAdminResponse issueImageUpload(long memberId, String contentType, Long contentLength) {
        return productAdminPresenter.toImageUploadResponse(productImageUploadProcessor.issue(memberId, contentType, contentLength));
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ProductCreateAdminResponse create(ProductCreateCommand command) {
        ProductDescriptionSanitizer.Result description = productDescriptionSanitizer.sanitize(command.descriptionHtml());
        productWriteProcessor.validate(command);
        List<Long> categoryIds = productWriteProcessor.displayCategoryIdsOf(command);
        long productId = snowflakeIdGenerator.generateId();
        var images = productImageAttachmentProcessor.attach(productId, command);
        var rolledBack = new java.util.concurrent.atomic.AtomicBoolean();
        // 컨텍스트 간 조율은 Facade 책임이다. 외부 I/O 전후를 제외한 DB 구간만 프로그램 방식으로 한정한다.
        TransactionTemplate registrationTransaction = new TransactionTemplate(transactionManager);
        registrationTransaction.setTimeout(LOCKING_TIMEOUT_SECONDS);
        try {
            var info = registrationTransaction.execute(status -> {
                // 카테고리 잠금 자체가 실패해도 확정 롤백 후 보상할 수 있게 첫 동작으로 등록한다.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int completion) {
                        if (completion == STATUS_ROLLED_BACK) {
                            rolledBack.set(true);
                        }
                    }
                });
                var lockedCategories = categoryProcessor.getTreeForUpdate();
                return productRegistrationProcessor.register(productId, command, description.html(), categoryIds, images, lockedCategories);
            });
            return productAdminPresenter.toCreateResponse(info, description.changed());
        } catch (RuntimeException exception) {
            // execute 가 트랜잭션 · connection 자원을 정리한 뒤 외부 삭제한다. 커밋 결과 불명에서는 표시가 없으므로 보존한다.
            if (rolledBack.get()) {
                productImageAttachmentProcessor.cleanup(images.stream()
                    .map(com.commercefoundry.domainlayer.product.domain.model.ProductImage::storageKey).toList());
            }
            if (exception instanceof QueryTimeoutException || exception instanceof TransactionTimedOutException
                || exception instanceof PessimisticLockingFailureException) {
                throw new ProductException(ProductErrorCode.CATEGORY_BUSY, exception);
            }
            throw exception;
        }
    }

    /** 상품과 자식 조회 사이의 수정 · 삭제가 섞이지 않게 같은 DB 스냅숏으로 읽는다. 외부 I/O 는 없다. */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductDetailAdminResponse getProduct(long productId) {
        return productAdminPresenter.toDetailResponse(productAdminDetailProcessor.getProduct(productId));
    }

    /**
     * 카테고리 조건은 하위 카테고리까지 펼친다 — 고객 목록과 같고 숨긴 카테고리도 포함한다. 없는 아이디는 빈 집합이라 어댑터가 쿼리 없이 빈 페이지를 준다.
     *
     * <p>목록 · count · SKU 집계를 한 스냅숏으로 읽는다 — READ COMMITTED 면 문장마다 스냅숏이 달라, 그 사이 재고가 바뀌면 "품절" 필터로 고른 행이
     * "판매중" 으로 표시되거나 총계가 어긋날 수 있다. 읽기 전용이라 직렬화 실패가 없다 (어드민 재고 조회와 같은 이유).
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<ProductListAdminItem> searchProducts(ProductAdminSearchCommand command) {
        Set<Long> categoryIds = command.categoryId() == null ? null : categoryProcessor.subtreeIdsOf(command.categoryId());
        return productAdminPresenter.toListPageResponse(productAdminQueryProcessor.search(command, categoryIds));
    }
}
