package com.commercefoundry.domainlayer.productdraft.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
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
 *
 * <p><b>감사 (#163 PR-C′)</b> — 생성 · 거절은 {@link ProductAdminAuditProcessor} 로 {@code product-draft:<id>} 대상 한 줄 남긴다(선례: {@code CategoryAdminFacade}).
 * 생성은 아이디를 받기 전에 실패하면 대상이 {@code -} 다. 트랜잭션 안에서 불러도 줄은 커밋 결과로 남는다(롤백되면 성공으로 부른 것도 {@code FAILURE}).
 * 업무 예외는 기록한 뒤 그대로 다시 던진다. 입력 사실 · 문구 · 이미지 · 생성 결과 · 거절 사유는 싣지 않는다 — 정본은 초안 행이다.
 */
@Service
@RequiredArgsConstructor
public class ProductDraftAdminFacade implements ProductDraftAdminUseCase {

    private final ProductDraftProcessor productDraftProcessor;
    private final ProductDraftAdminPresenter productDraftAdminPresenter;
    private final ProductAdminAuditProcessor productAdminAuditProcessor;

    @Override
    @Transactional
    public ProductDraftAdminResponse create(ProductDraftCreateCommand command) {
        ProductDraft created;
        try {
            created = productDraftProcessor.create(command);
        } catch (RuntimeException exception) {
            productAdminAuditProcessor.productDraftCreated(command.requestedByMemberId(), null, false);
            throw exception;
        }
        productAdminAuditProcessor.productDraftCreated(command.requestedByMemberId(), created.id(), true);
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
        ProductDraftAdminResponse response;
        try {
            response = rejectOrBusy(draftId, memberId);
        } catch (RuntimeException exception) {
            productAdminAuditProcessor.productDraftRejected(memberId, draftId, false);
            throw exception;
        }
        productAdminAuditProcessor.productDraftRejected(memberId, draftId, true);
        return response;
    }

    /** 낙관적 락 충돌은 409 {@code PRODUCT_DRAFT_025} 로 바꾼다 — 감사 try 안에서 불러 바꾼 예외도 {@code FAILURE} 로 남는다. */
    private ProductDraftAdminResponse rejectOrBusy(long draftId, long memberId) {
        Instant now = productDraftProcessor.now();
        try {
            return productDraftAdminPresenter.toResponse(productDraftProcessor.reject(draftId, memberId, now), now);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new ProductDraftException(ProductDraftErrorCode.DRAFT_BUSY, exception);
        }
    }
}
