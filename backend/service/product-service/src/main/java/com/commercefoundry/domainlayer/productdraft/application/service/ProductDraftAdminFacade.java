package com.commercefoundry.domainlayer.productdraft.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.response.ProductDraftAdminResponse;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.presenter.ProductDraftAdminPresenter;
import com.commercefoundry.domainlayer.productdraft.application.command.ProductDraftCreateCommand;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.in.ProductDraftAdminUseCase;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftImageProcessor;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftProcessor;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 AI 상품 초안. 응답의 상태 · 누락 입력은 한 시각({@code now})으로 판정한다.
 *
 * <p><b>트랜잭션</b> — 생성은 이미지 저장소 HEAD(외부 I/O)가 섞여 Facade 에 트랜잭션을 걸지 않는다: 판정(I/O 없음) → 키 · ETag 고정(트랜잭션 밖) →
 * 저장(Processor 의 짧은 쓰기 트랜잭션) 순이다. 조회 · 거절은 외부 I/O 가 없어 Facade 에 건다.
 * 한계: 저장이 커밋된 뒤 감사 · Presenter 가 예외를 던지면 행은 남고 응답은 500 이라, 어드민이 다시 보내면 초안이 하나 더 생길 수 있다(멱등 키 없음 — 7일 TTL 로 정리).
 *
 * <p>거절은 초안 행의 {@code @Version} 으로 동시 변경을 막는다 — 다른 쪽이 먼저 바꿨으면 409 · {@code PRODUCT_DRAFT_025} 다.
 *
 * <p><b>감사 (#163 PR-C′)</b> — 생성 · 거절은 {@link ProductAdminAuditProcessor} 로 {@code product-draft:<id>} 대상 한 줄 남긴다(선례: {@code CategoryAdminFacade}).
 * 생성은 아이디를 받기 전에 실패하면 대상이 {@code -} 다. 생성은 저장 트랜잭션이 끝난 뒤 남긴다. 거절은 트랜잭션 안에서 불러도 줄은 커밋 결과로 남는다
 * (롤백되면 성공으로 부른 것도 {@code FAILURE}). 업무 예외는 기록한 뒤 그대로 다시 던진다. 입력 사실 · 문구 · 이미지 · 생성 결과 · 거절 사유는 싣지 않는다 — 정본은 초안 행이다.
 */
@Service
@RequiredArgsConstructor
public class ProductDraftAdminFacade implements ProductDraftAdminUseCase {

    private final ProductDraftProcessor productDraftProcessor;
    private final ProductDraftImageProcessor productDraftImageProcessor;
    private final ProductDraftAdminPresenter productDraftAdminPresenter;
    private final ProductAdminAuditProcessor productAdminAuditProcessor;

    @Override
    public ProductDraftAdminResponse create(ProductDraftCreateCommand command) {
        ProductDraft created;
        try {
            productDraftProcessor.validateImages(command.images());
            ProductDraftCreateCommand pinned = command.withImages(productDraftImageProcessor.pin(command.images()));
            created = productDraftProcessor.create(pinned);
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
