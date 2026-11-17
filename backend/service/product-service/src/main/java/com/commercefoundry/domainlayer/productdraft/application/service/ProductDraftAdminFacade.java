package com.commercefoundry.domainlayer.productdraft.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.response.ProductDraftAdminResponse;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.presenter.ProductDraftAdminPresenter;
import com.commercefoundry.domainlayer.productdraft.application.command.ProductDraftCreateCommand;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.in.ProductDraftAdminUseCase;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftGenerationProcessor;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftImageProcessor;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftProcessor;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.global.properties.ProductDraftProperties;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 어드민 AI 상품 초안. 응답의 상태 · 누락 입력은 한 시각({@code now})으로 판정한다.
 *
 * <p><b>트랜잭션</b> — 생성은 이미지 저장소 HEAD(외부 I/O)가 섞여 Facade 에 트랜잭션을 걸지 않는다: 판정(I/O 없음) → 키 · ETag 고정(트랜잭션 밖) →
 * 저장(Processor 의 짧은 쓰기 트랜잭션) 순이다. 생성 시작도 operations 등록(Feign)이 섞여 Facade 에 걸지 않는다: claim(짧은 쓰기) → 등록(트랜잭션 밖) →
 * 작업 연결(짧은 쓰기) 순이다 — 원격 호출 동안 행 · 커넥션을 잡지 않는다. 조회 · 거절은 외부 I/O 가 없어 Facade 에 건다.
 * 한계: 저장이 커밋된 뒤 감사 · Presenter 가 예외를 던지면 행은 남고 응답은 500 이라, 어드민이 다시 보내면 초안이 하나 더 생길 수 있다(멱등 키 없음 — 7일 TTL 로 정리).
 *
 * <p><b>생성 시작 (#359 PR-B1, D14)</b> — 플래그 {@code product-draft.generation-enabled} 가 열려 있으면 초안 저장 뒤 1회차를 시작한다. 시작이 실패해도 저장은 201 이고
 * 응답은 생성 대기 초안이다 — 운영자는 {@code POST /{draftId}/generate} 로 다시 시도한다. claim 은 회차 · 시작한 어드민 · 입력 hash 를 저장하고, 작업 연결 전 실패는
 * 그 claim 을 그대로 둔다 — 재시도가 같은 값으로 등록해 operations 가 같은 영수증을 재생한다(작업이 둘 생기지 않는다).
 *
 * <p>거절 · 생성 시작은 초안 행의 {@code @Version} 으로 동시 변경을 막는다 — 다른 쪽이 먼저 바꿨으면 409 · {@code PRODUCT_DRAFT_025} 다.
 *
 * <p><b>감사 (#163 PR-C′)</b> — 생성 · 거절 · 생성 시작은 {@link ProductAdminAuditProcessor} 로 {@code product-draft:<id>} 대상 한 줄 남긴다(선례: {@code CategoryAdminFacade}).
 * 생성은 아이디를 받기 전에 실패하면 대상이 {@code -} 다. 생성은 저장 트랜잭션이 끝난 뒤 남기고, 저장 직후 자동 시작은 생성 줄에 포함한다(따로 남기지 않는다).
 * 거절은 트랜잭션 안에서 불러도 줄은 커밋 결과로 남는다(롤백되면 성공으로 부른 것도 {@code FAILURE}). 업무 예외는 기록한 뒤 그대로 다시 던진다.
 * 입력 사실 · 문구 · 이미지 · 생성 결과 · 거절 사유 · 작업 아이디는 싣지 않는다 — 정본은 초안 행이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductDraftAdminFacade implements ProductDraftAdminUseCase {

    private final ProductDraftProcessor productDraftProcessor;
    private final ProductDraftImageProcessor productDraftImageProcessor;
    private final ProductDraftGenerationProcessor productDraftGenerationProcessor;
    private final ProductDraftProperties productDraftProperties;
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
        ProductDraft result = created;
        // 상위 쓰기 트랜잭션이 있으면 커밋 전 원격 등록을 하지 않는다. 응답 draftId 로 명시 재시도한다.
        if (productDraftProperties.generationEnabled() && !TransactionSynchronizationManager.isActualTransactionActive()) {
            result = startAfterCreate(created);
        }
        return productDraftAdminPresenter.toResponse(result, productDraftProcessor.now());
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

    @Override
    public ProductDraftAdminResponse generate(long draftId, long memberId) {
        ProductDraft started;
        try {
            if (!productDraftProperties.generationEnabled()) {
                throw new ProductDraftException(ProductDraftErrorCode.GENERATION_START_DISABLED);
            }
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new ProductDraftException(ProductDraftErrorCode.GENERATION_UNAVAILABLE);
            }
            started = start(draftId, memberId);
        } catch (RuntimeException exception) {
            productAdminAuditProcessor.productDraftGenerated(memberId, draftId, false);
            throw exception;
        }
        productAdminAuditProcessor.productDraftGenerated(memberId, draftId, true);
        return productDraftAdminPresenter.toResponse(started, productDraftProcessor.now());
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

    /** 저장 직후 자동 시작(D14). 어떤 실패도 저장 결과(201)를 바꾸지 않는다 — 로그만 남기고 생성 대기 초안을 돌려준다. 로그에 입력 · 원격 본문을 싣지 않는다. */
    private ProductDraft startAfterCreate(ProductDraft created) {
        try {
            return start(created.id(), created.requestedByMemberId());
        } catch (ProductDraftException exception) {
            log.warn("product draft generation did not start after create. draftId={}, code={}", created.id(), exception.getErrorCode().getCode());
        } catch (RuntimeException exception) {
            log.error("product draft generation failed unexpectedly after create. draftId={}, exception={}", created.id(), exception.getClass().getSimpleName());
        }
        return created;
    }

    /** claim(짧은 쓰기) → 생성 중이면 그대로 → 등록(트랜잭션 밖) → 작업 연결(짧은 쓰기). */
    private ProductDraft start(long draftId, long memberId) {
        ProductDraft claimed;
        try {
            claimed = productDraftGenerationProcessor.claim(draftId, memberId);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new ProductDraftException(ProductDraftErrorCode.DRAFT_BUSY, exception);
        }
        if (!claimed.awaitingGenerationJob()) {
            return claimed;
        }
        return bind(claimed, productDraftGenerationProcessor.register(claimed));
    }

    /**
     * 작업 연결. 낙관적 락이 깨지면 한 번 다시 읽어 판정한다 — 같은 작업을 동시 재시도가 먼저 붙였으면 그 결과가 성공이다.
     * 연결을 저장하지 못하면(DB 장애) 원격 등록은 이미 됐을 수 있어 {@code 502} 로 재시도를 안내한다 — 재시도는 영수증 재생으로 같은 작업을 받는다.
     */
    private ProductDraft bind(ProductDraft claimed, long jobId) {
        try {
            try {
                return productDraftGenerationProcessor.bind(claimed, jobId);
            } catch (ObjectOptimisticLockingFailureException exception) {
                return productDraftGenerationProcessor.bind(claimed, jobId);
            }
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new ProductDraftException(ProductDraftErrorCode.DRAFT_BUSY, exception);
        } catch (DataAccessException exception) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_UNAVAILABLE, exception);
        }
    }
}
