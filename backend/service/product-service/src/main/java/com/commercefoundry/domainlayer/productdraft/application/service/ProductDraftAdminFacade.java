package com.commercefoundry.domainlayer.productdraft.application.service;

import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.response.ProductDraftAdminResponse;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.presenter.ProductDraftAdminPresenter;
import com.commercefoundry.domainlayer.productdraft.application.command.ProductDraftCreateCommand;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.in.ProductDraftAdminUseCase;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftProcessor;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 AI 상품 초안. 외부 I/O 가 없어 Facade 에 트랜잭션을 건다. 응답의 상태 · 누락 입력은 한 시각({@code now})으로 판정한다.
 *
 * <p>거절은 초안 행의 {@code @Version} 으로 동시 변경을 막는다 — 다른 쪽이 먼저 바꿨으면 409 · {@code PRODUCT_DRAFT_025} 다.
 */
@Service
@RequiredArgsConstructor
public class ProductDraftAdminFacade implements ProductDraftAdminUseCase {

    private final ProductDraftProcessor productDraftProcessor;
    private final ProductDraftAdminPresenter productDraftAdminPresenter;

    @Override
    @Transactional
    public ProductDraftAdminResponse create(ProductDraftCreateCommand command) {
        ProductDraft created = productDraftProcessor.create(command);
        return productDraftAdminPresenter.toResponse(created, productDraftProcessor.now());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductDraftAdminResponse getDraft(long draftId) {
        return productDraftAdminPresenter.toResponse(productDraftProcessor.getDraft(draftId), productDraftProcessor.now());
    }

    @Override
    @Transactional
    public ProductDraftAdminResponse reject(long draftId, long memberId) {
        Instant now = productDraftProcessor.now();
        try {
            return productDraftAdminPresenter.toResponse(productDraftProcessor.reject(draftId, memberId, now), now);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new ProductDraftException(ProductDraftErrorCode.DRAFT_BUSY, exception);
        }
    }
}
